# vexelray-framework — TODO

## Native-image builds on Linux

The framework exists so applications compile to a GraalVM native binary, but every native-image build so far has been
on Windows. On Linux the applications have only run through `mvn exec:exec` on a regular JDK. Install GraalVM in the
VM and build the GUI demo and one suite application with `-Pnative`, then fix what breaks. Expect:

- the per-OS modules found through `ServiceLoader` (windowing, input, clipboard, file dialogs, pty, FFmpeg and
  imagelib natives) need their `META-INF/services` and resource descriptors included in the image, and their
  Panama downcalls registered (`reachability-metadata` / `--enable-native-access`);
- the shared libraries the Linux modules extract (libnfd, the ffwrap shim and FFmpeg, imagelib) must be found next to
  the binary or extracted at run time, as on Windows;
- the template's native profile and the installers assume `.exe` names and Windows paths.

Native-specific code belongs in `-linux` modules like the rest; the template and profiles should only choose them.
