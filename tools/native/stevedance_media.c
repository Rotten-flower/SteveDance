/* Small Windows video/image decoder. Only the owning Java worker touches a movie.
 * 小型视频/图片解码器；影片状态只由对应后台线程读写。 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>

typedef struct {
    AVFormatContext *format;
    AVCodecContext *codec;
    AVFrame *frame;
    AVPacket *packet;
    struct SwsContext *scale;
    int stream, eof, current, count;
    int64_t first_pts;
    double fps;
} Movie;

static void error(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message);
}
static int dimensions(int w, int h) {
    return w > 0 && h > 0 && w <= 4096 && h <= 4096 && (int64_t)w*h <= 4194304;
}
static void release(Movie *m) {
    if (!m) return;
    sws_freeContext(m->scale);
    av_packet_free(&m->packet);
    av_frame_free(&m->frame);
    avcodec_free_context(&m->codec);
    avformat_close_input(&m->format);
    free(m);
}
/* Receive before sending: packets with B-frames may yield delayed output.
 * 先取解码输出再送包，保留B帧的呈现顺序和结尾排空。 */
static int next(Movie *m) {
    for (;;) {
        int result = avcodec_receive_frame(m->codec, m->frame);
        if (result >= 0) return 1;
        if (result == AVERROR_EOF) return 0;
        if (result != AVERROR(EAGAIN)) return -1;
        if (m->eof) return 0;
        do {
            av_packet_unref(m->packet);
            result = av_read_frame(m->format, m->packet);
        } while (result >= 0 && m->packet->stream_index != m->stream);
        if (result < 0) {
            m->eof = 1;
            result = avcodec_send_packet(m->codec, NULL);
        } else {
            result = avcodec_send_packet(m->codec, m->packet);
        }
        av_packet_unref(m->packet);
        if (result < 0) return -1;
    }
}
/* Return width/height (little endian) then tightly packed RGBA. */
static jbyteArray pixels(JNIEnv *env, AVFrame *frame, struct SwsContext **scale) {
    int w=frame->width,h=frame->height;
    if (!dimensions(w,h)) { error(env,"Decoded dimensions exceed budget"); return NULL; }
    int size=w*h*4;
    uint8_t *buffer=av_malloc((size_t)size+8);
    if (!buffer) { error(env,"Cannot allocate decoded image"); return NULL; }
    for(int i=0;i<4;i++){buffer[i]=(uint32_t)w>>(8*i);buffer[4+i]=(uint32_t)h>>(8*i);}
    *scale=sws_getCachedContext(*scale,w,h,frame->format,w,h,AV_PIX_FMT_RGBA,SWS_BICUBIC,NULL,NULL,NULL);
    if (!*scale) { av_free(buffer); error(env,"Cannot convert decoded image"); return NULL; }
    uint8_t *output[4]={buffer+8,NULL,NULL,NULL}; int stride[4]={w*4,0,0,0};
    int rows=sws_scale(*scale,(const uint8_t * const*)frame->data,frame->linesize,0,h,output,stride);
    jbyteArray result=NULL;
    if(rows==h){result=(*env)->NewByteArray(env,size+8);if(result)(*env)->SetByteArrayRegion(env,result,0,size+8,(jbyte*)buffer);}
    else error(env,"Incomplete decoded image");
    av_free(buffer);return result;
}
JNIEXPORT jlong JNICALL Java_dev_flower_stevedance_media_MovieNative_open(JNIEnv *env,jclass type,jbyteArray path,jdouble fps,jint count) {
    (void)type;
    int length=(*env)->GetArrayLength(env,path);
    if(length<1 || length>32768){error(env,"Movie path exceeds budget");return 0;}
    /* FFmpeg accepts standard UTF-8, not JNI's modified UTF-8 (emoji paths).
     * 使用标准UTF-8路径，避免含增补字符的目录无法打开。 */
    char *name=calloc((size_t)length+1,1);
    if (!name) return 0;
    (*env)->GetByteArrayRegion(env,path,0,length,(jbyte*)name);
    if((*env)->ExceptionCheck(env)){free(name);return 0;}
    Movie *m=calloc(1,sizeof(Movie));
    if(!m){free(name);error(env,"Cannot allocate movie");return 0;}
    m->fps=fps;m->count=count;m->current=0;
    int result=avformat_open_input(&m->format,name,NULL,NULL);
    free(name);
    if(result<0 || avformat_find_stream_info(m->format,NULL)<0)goto fail;
    m->stream=av_find_best_stream(m->format,AVMEDIA_TYPE_VIDEO,-1,-1,NULL,0);
    if(m->stream<0)goto fail;
    AVStream *stream=m->format->streams[m->stream];
    AVRational rate=stream->r_frame_rate;
    if(rate.num>0 && rate.den>0 && fabs(av_q2d(rate)-fps)>.2)goto fail;
    const AVCodec *decoder=avcodec_find_decoder(stream->codecpar->codec_id);
    if(!decoder)goto fail;
    m->codec=avcodec_alloc_context3(decoder);
    if(!m->codec || avcodec_parameters_to_context(m->codec,stream->codecpar)<0)goto fail;
    m->codec->thread_count=2;
    m->codec->max_pixels=4194304;
    if(avcodec_open2(m->codec,decoder,NULL)<0)goto fail;
    m->frame=av_frame_alloc();m->packet=av_packet_alloc();
    if(!m->frame || !m->packet || next(m)!=1 || !dimensions(m->frame->width,m->frame->height))goto fail;
    m->first_pts=m->frame->best_effort_timestamp;
    if(m->first_pts==AV_NOPTS_VALUE)goto fail;
    return (jlong)(intptr_t)m;
fail:
    release(m);error(env,"Cannot open PV movie or unsupported clock/codec");return 0;
}
JNIEXPORT jbyteArray JNICALL Java_dev_flower_stevedance_media_MovieNative_read(JNIEnv *env,jclass type,jlong handle,jint target) {
    (void)type;Movie *m=(Movie*)(intptr_t)handle;
    if(!m || target<0 || target>=m->count){error(env,"Movie frame outside range");return NULL;}
    if(target!=m->current){
        if(target<m->current || target-m->current>8){
            AVRational time=m->format->streams[m->stream]->time_base;
            int64_t pts=m->first_pts+(int64_t)llround((double)target/m->fps/av_q2d(time));
            if(av_seek_frame(m->format,m->stream,pts,AVSEEK_FLAG_BACKWARD)<0){error(env,"Cannot seek movie");return NULL;}
            avcodec_flush_buffers(m->codec);m->eof=0;m->current=-1;
        }
        while(m->current<target){
            if(next(m)!=1){error(env,"Unexpected movie end");return NULL;}
            int64_t pts=m->frame->best_effort_timestamp;
            if(pts==AV_NOPTS_VALUE){error(env,"Missing movie presentation time");return NULL;}
            m->current=(int)llround((pts-m->first_pts)*av_q2d(m->format->streams[m->stream]->time_base)*m->fps);
        }
        if(m->current!=target){error(env,"Movie frame clock mismatch");return NULL;}
    }
    return pixels(env,m->frame,&m->scale);
}
JNIEXPORT void JNICALL Java_dev_flower_stevedance_media_MovieNative_close(JNIEnv *env,jclass type,jlong handle){(void)env;(void)type;release((Movie*)(intptr_t)handle);}
JNIEXPORT jbyteArray JNICALL Java_dev_flower_stevedance_media_MovieNative_decodeImage(JNIEnv *env,jclass type,jbyteArray data){
    (void)type; int size=(*env)->GetArrayLength(env,data);
    if(size<12 || size>33554432){error(env,"Image file exceeds budget");return NULL;}
    const AVCodec *decoder=avcodec_find_decoder(AV_CODEC_ID_WEBP);
    AVCodecContext *codec=avcodec_alloc_context3(decoder);
    AVPacket *packet=av_packet_alloc();AVFrame *frame=av_frame_alloc();
    struct SwsContext *scale=NULL;jbyteArray result=NULL;
    if(!codec||!packet||!frame)goto done;
    codec->max_pixels=4194304;
    if(avcodec_open2(codec,decoder,NULL)<0||av_new_packet(packet,size)<0)goto done;
    (*env)->GetByteArrayRegion(env,data,0,size,(jbyte*)packet->data);
    if((*env)->ExceptionCheck(env))goto done;
    if(avcodec_send_packet(codec,packet)<0||avcodec_receive_frame(codec,frame)<0)goto done;
    result=pixels(env,frame,&scale);
done:
    sws_freeContext(scale);av_frame_free(&frame);av_packet_free(&packet);avcodec_free_context(&codec);
    if(!result&&!(*env)->ExceptionCheck(env))error(env,"Cannot decode WebP image");return result;
}
