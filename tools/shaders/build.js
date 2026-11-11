const path = require('path');
const fs = require('fs');
const shaders = {
  // FP32 ALU: 2 dependent vec4 FMA chains per iteration = 16 FLOP/iter, 512 iters, unrolled x4
  fma32: `#version 450
layout(local_size_x = 256) in;
layout(std430, binding = 0) writeonly buffer Out { vec4 data[]; };
layout(push_constant) uniform PC { float seed; uint iters; } pc;
void main() {
  uint id = gl_GlobalInvocationID.x;
  vec4 x = vec4(float(id & 1023u) * 1e-4 + pc.seed);
  vec4 y = x + vec4(0.5, 0.25, 0.125, 0.0625);
  const vec4 m = vec4(0.9999);
  const vec4 a = vec4(0.0001);
  for (uint i = 0u; i < pc.iters; i++) {
    x = fma(x, m, a); y = fma(y, m, a);
    x = fma(x, m, a); y = fma(y, m, a);
    x = fma(x, m, a); y = fma(y, m, a);
    x = fma(x, m, a); y = fma(y, m, a);
  }
  data[id] = x + y;
}`,
  // Compute memory bandwidth: read one vec4, write one vec4 per invocation
  copy: `#version 450
layout(local_size_x = 256) in;
layout(std430, binding = 0) readonly buffer Src { vec4 a[]; };
layout(std430, binding = 1) writeonly buffer Dst { vec4 b[]; };
void main() {
  uint i = gl_GlobalInvocationID.x;
  b[i] = a[i] * 1.0001;
}`,
};
require(path.join(__dirname, 'node_modules/@webgpu/glslang/dist/node-devel/glslang.js'))().then((glslang) => {
  delete glslang.then;
  let out = '// Generated SPIR-V (glslang). Do not edit; see tools/shaders.\n#pragma once\n#include <cstdint>\n';
  for (const [name, src] of Object.entries(shaders)) {
    const words = glslang.compileGLSL(src, 'compute', false);
    out += `static const uint32_t kSpv_${name}[] = {` + Array.from(words).map(w => '0x' + w.toString(16).padStart(8, '0')).join(',') + '};\n';
    fs.writeFileSync(path.join(__dirname, name + '.comp'), src);
    console.log(name, words.length, 'words', '0x' + words[0].toString(16));
  }
  fs.writeFileSync(process.argv[2], out);
  process.exit(0);
});
