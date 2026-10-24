# Third-party license texts

These files preserve upstream license texts; they do not grant additional rights over original RTM, NGTLib or MCTE material. Existing code notices and any separately applicable permissions remain relevant.

- fixRTM: https://github.com/fixrtm/fixRTM (LGPL v3 with the original-author exception). LGPL text and Kotlin/WebP supplements are copied from the local fixRTM source archive marked 2.0.29-beta.1; its exact commit has not been established.
- GPL v3 text: GNU GCC project's COPYING3, https://github.com/gcc-mirror/gcc/blob/master/COPYING3 .
- Sai 0.0.2: LICENSE.txt copied verbatim from the resolved artifact. The main license is MPL 2.0; retain its additional component notices.
- jbsdiff 1.0: BSD two-clause text from https://github.com/malensek/jbsdiff/blob/master/LICENSE.txt .
- Kotlin 1.4.32: Apache 2.0.
- webp-imageio 0.1.6: Apache 2.0, with the upstream Luciad notice.

The build additionally collects notices present in every bundled dependency JAR into META-INF/licenses/dependencies/<group>/<artifact>/<version>/ and writes its coordinates to DEPENDENCIES.txt. This prevents same-named LICENSE/NOTICE files from being silently lost during flattening. Check the resulting artifact and corresponding-source availability before distribution.
