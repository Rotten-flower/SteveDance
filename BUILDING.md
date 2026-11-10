# Building / 构建

Use JDK 17 and the included Gradle 8.8 Wrapper. The first build downloads Forge and other dependencies.

使用 JDK 17 和工程自带的 Gradle 8.8 Wrapper。首次构建需要联网下载 Forge 等依赖。

```powershell
cd forge-1.20.1
.\gradlew.bat build jarJar reobfJarJar --console=plain
```

Install the unclassified `build/libs/stevedance-forge-1.20.1-0.1.0.jar`. The `slim` JAR is an intermediate artifact.

安装不带 `slim` 后缀的 JAR，`slim` 是构建中间产物。

`common/src/main/java` and `forge-1.20.1/src/main/java` contain the Java implementation. `release-resources` contains the packed base resources and five included song packs. Building does not require the maintainer's extraction directories or Python tools. Resources keep their separate rights described in CREDITS.md.

上述两个源码目录包含 Java 实现，`release-resources` 包含基础资源和五首内置曲包。构建不依赖维护者的解包目录或 Python 工具。资源的权利归属见 CREDITS.md。

To rebuild the optional F2 addon, extract `assets/stevedance_f2/bundled-packs` from its release JAR, preserving `index.json` and all 39 ZIPs. Run:

重新打包 F2 附加包时，从发布 JAR 提取上述目录，保留 `index.json` 和39个 ZIP，然后执行：

```powershell
.\gradlew.bat -Pf2Packs=<local-pack-directory> f2AddonJar --console=plain
```

The normal core build does not require the F2 addon.

本体构建不依赖 F2 附加包。

The Windows decoder JNI source is in `tools/native`. FFmpeg 7.1.2 source and relink archives are included in `third-party/ffmpeg`. The native rebuild requires w64devkit 2.11.0 and JDK 17; the resource manifest must be updated with the rebuilt DLL hash.

Windows 解码器 JNI 源码位于 `tools/native`。FFmpeg 源码与重链接材料位于 `third-party/ffmpeg`，重建需要 w64devkit 2.11.0 和 JDK 17；替换 DLL 后同步资源清单中的哈希。

