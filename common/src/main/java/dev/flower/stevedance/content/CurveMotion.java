package dev.flower.stevedance.content;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.security.*;
import com.google.gson.*;
import org.tukaani.xz.XZInputStream;

/** Bounded curve reconstruction into the existing DMOT layout. 有界曲线恢复，保留原采样器。 */
final class CurveMotion {
    static void normalize(double[] q) {
        double norm=0;for(int k=3;k<7;k++)norm+=q[k]*q[k];norm=Math.sqrt(norm);
        for(int k=3;k<7;k++)q[k]/=norm;
    }
    static float[] decode(byte[] data) throws Exception {
        if(!new String(data,0,8,java.nio.charset.StandardCharsets.US_ASCII).equals("SDCURV01"))throw new IOException("Magic");
        ByteBuffer head=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] raw;try(var in=new XZInputStream(new ByteArrayInputStream(data,44,data.length-44),65536)){raw=in.readNBytes(head.getInt(8)+1);}
        if(raw.length!=head.getInt(8)||!Arrays.equals(MessageDigest.getInstance("SHA-256").digest(raw),Arrays.copyOfRange(data,12,44)))throw new IOException("Hash");
        ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);int size=b.getInt();if(size<1||size>raw.length-4||size>8*1024*1024)throw new IOException("Curve metadata size");byte[] json=new byte[size];b.get(json);
        JsonObject m=JsonParser.parseString(new String(json,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        int frames=m.getAsJsonArray("header").get(3).getAsInt(),bones=m.getAsJsonArray("header").get(4).getAsInt();
        if(frames<2||frames>30000||bones<1||bones>64||20L+frames*(long)bones*28>40*1024*1024)throw new IOException("Curve dimensions");
        int[] ends=new int[bones];Arrays.fill(ends,-1);
        double[] out=new double[frames*bones*7];
        for(JsonElement item:m.getAsJsonArray("records")) {
            JsonArray r=item.getAsJsonArray();int bone=r.get(0).getAsInt(),start=r.get(1).getAsInt(),end=r.get(2).getAsInt(),count=r.get(3).getAsInt();
            double ps=r.get(4).getAsDouble(),qs=r.get(5).getAsDouble();
            if(bone<0||bone>=bones||start<0||end<=start||end>=frames||end-start>512||count<2||count>end-start+1||start!=(ends[bone]<0?0:ends[bone])||!Double.isFinite(ps)||!Double.isFinite(qs)||ps<=0||qs<=0)throw new IOException("Curve segment");
            ends[bone]=end;long[][] q=new long[count][7];
            double[] times=new double[count];for(int j=0;j<count;j++)times[j]=r.size()>6?r.get(6).getAsJsonArray().get(j).getAsDouble():start+(double)(end-start)*j/(count-1);
            for(int k=0;k<7;k++)for(int j=0;j<count;j++) {
                long d=b.getInt();q[j][k]=j==0?d:j==1?q[j-1][k]+d:2*q[j-1][k]-q[j-2][k]+d;
            }
            double[][] v=new double[count][7];
            for(int j=0;j<count;j++){for(int k=0;k<7;k++)v[j][k]=q[j][k]*(k<3?ps:qs);normalize(v[j]);}
            for(int f=start;f<=end;f++) {
                int i=Arrays.binarySearch(times,(double)f);i=i<0?-i-2:i;i=Math.max(0,Math.min(count-2,i));double t=(f-times[i])/(times[i+1]-times[i]),dot=0;
                for(int k=3;k<7;k++)dot+=v[i][k]*v[i+1][k];double sign=dot<0?-1:1;
                double[] x=new double[7];for(int k=0;k<7;k++)x[k]=v[i][k]*(1-t)+v[i+1][k]*t*(k<3?1:sign);normalize(x);
                for(int k=0;k<7;k++)out[(f*bones+bone)*7+k]=x[k];
            }
        }
        if(b.hasRemaining())throw new IOException("Trailing payload");for(int end:ends)if(end!=frames-1)throw new IOException("Incomplete curve track");
        if(m.has("parents")) {
            JsonArray parents=m.getAsJsonArray("parents");if(parents.size()!=bones)throw new IOException("Curve parents");for(int i=0;i<bones;i++)if(parents.get(i).getAsInt() < -1||parents.get(i).getAsInt()>=i)throw new IOException("Curve parent order");
            for(int f=0;f<frames;f++)for(int bone=0;bone<bones;bone++) {
                int parent=parents.get(bone).getAsInt();if(parent<0)continue;
                int a=(f*bones+parent)*7,c=(f*bones+bone)*7;
                double x=out[a+3],y=out[a+4],z=out[a+5],w=out[a+6];
                double norm=Math.sqrt(x*x+y*y+z*z+w*w);x/=norm;y/=norm;z/=norm;w/=norm;
                double px=out[c],py=out[c+1],pz=out[c+2];
                double tx=2*(y*pz-z*py),ty=2*(z*px-x*pz),tz=2*(x*py-y*px);
                out[c]=out[a]+px+w*tx+y*tz-z*ty;out[c+1]=out[a+1]+py+w*ty+z*tx-x*tz;out[c+2]=out[a+2]+pz+w*tz+x*ty-y*tx;
                double bx=out[c+3],by=out[c+4],bz=out[c+5],bw=out[c+6];
                double[] q={0,0,0,x*bw+bx*w+y*bz-z*by,y*bw+by*w+z*bx-x*bz,z*bw+bz*w+x*by-y*bx,w*bw-x*bx-y*by-z*bz};normalize(q);
                for(int k=3;k<7;k++)out[c+k]=q[k];
            }
        }
        float[] result=new float[out.length];for(int i=0;i<out.length;i++)result[i]=(float)out[i];return result;
    }
    static byte[] read(byte[] data) throws IOException {
        try {
            ByteBuffer h=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if(data.length<44 || h.getInt(8)<4 || h.getInt(8)>40*1024*1024)throw new IOException("Curve payload exceeds budget");
            float[] poses=decode(data);
            byte[] raw;
            try(var input=new XZInputStream(new ByteArrayInputStream(data,44,data.length-44),65536)){
                raw=input.readNBytes(h.getInt(8)+1);
            }
            ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);int size=b.getInt();
            byte[] json=new byte[size];b.get(json);
            JsonArray header=JsonParser.parseString(new String(json,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("header");
            ByteBuffer out=ByteBuffer.allocate(20+poses.length*4).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<5;i++)out.putInt(header.get(i).getAsInt());
            for(float f:poses){if(!Float.isFinite(f))throw new IOException("Nonfinite curve pose");out.putFloat(f);}
            return out.array();
        } catch(IOException ex){throw ex;} catch(Exception ex){throw new IOException("Invalid motion curve",ex);}
    }
}
