FFmpeg 7.1.2 corresponding source / 对应源码

The source archive is split to allow reliable uploads. Reassemble with Python / 使用 Python 合并：

```python
from pathlib import Path
p = Path("third-party/ffmpeg")
out = p / "ffmpeg-7.1.2.tar.xz"
out.write_bytes(b"".join(x.read_bytes() for x in sorted(p.glob("ffmpeg-7.1.2.tar.xz.part*"))))
```

SHA-256: `089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304`
