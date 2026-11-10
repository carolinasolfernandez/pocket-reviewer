// Generates ic_launcher.png for each mipmap folder
const zlib = require('zlib');
const fs   = require('fs');
const path = require('path');

// Standard Android launcher icon sizes
const SIZES = {
  'mipmap-mdpi':    48,
  'mipmap-hdpi':    72,
  'mipmap-xhdpi':   96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi':192,
};

function createPNG(size) {
  const pixels = new Uint8Array(size * size * 4);
  const cx = size / 2, cy = size / 2, r = size * 0.42;

  // Indigo background with gradient
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const idx = (y * size + x) * 4;
      const dist = Math.sqrt((x-cx)**2 + (y-cy)**2);
      if (dist <= r) {
        const t = dist / r;
        pixels[idx]   = Math.round(99  + (79  - 99)  * t * 0.4);
        pixels[idx+1] = Math.round(102 + (70  - 102) * t * 0.4);
        pixels[idx+2] = Math.round(241 + (229 - 241) * t * 0.4);
        pixels[idx+3] = 255;
      }
    }
  }
  drawSymbol(pixels, size);
  return encodePNG(pixels, size, size);
}

function setPixel(pixels, w, h, x, y, color, thick = 1) {
  for (let dy = -Math.floor(thick/2); dy <= Math.floor(thick/2); dy++) {
    for (let dx = -Math.floor(thick/2); dx <= Math.floor(thick/2); dx++) {
      const px = Math.round(x)+dx, py = Math.round(y)+dy;
      if (px < 0 || px >= w || py < 0 || py >= h) continue;
      const idx = (py * w + px) * 4;
      if (pixels[idx+3] === 0) continue;
      pixels[idx] = color.r; pixels[idx+1] = color.g;
      pixels[idx+2] = color.b; pixels[idx+3] = 255;
    }
  }
}

function line(pixels, w, h, x0, y0, x1, y1, color, thick) {
  const steps = Math.max(Math.abs(x1-x0), Math.abs(y1-y0)) * 2;
  for (let i = 0; i <= steps; i++) {
    const t = steps ? i/steps : 0;
    setPixel(pixels, w, h, x0+(x1-x0)*t, y0+(y1-y0)*t, color, thick);
  }
}

function drawSymbol(pixels, s) {
  const white = {r:255, g:255, b:255};
  const t = Math.max(1, Math.round(s * 0.07));
  const cx = s/2, cy = s/2, h = s*0.22, w = s*0.13;
  // <
  line(pixels, s, s, cx-s*0.18, cy-h, cx-s*0.18-w, cy, white, t);
  line(pixels, s, s, cx-s*0.18-w, cy, cx-s*0.18, cy+h, white, t);
  // >
  line(pixels, s, s, cx+s*0.18, cy-h, cx+s*0.18+w, cy, white, t);
  line(pixels, s, s, cx+s*0.18+w, cy, cx+s*0.18, cy+h, white, t);
  // /
  line(pixels, s, s, cx+s*0.06, cy-h*1.1, cx-s*0.06, cy+h*1.1, white, t);
}

function encodePNG(pixels, w, h) {
  const raw = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) {
    raw[y*(w*4+1)] = 0;
    for (let x = 0; x < w; x++) {
      const s = (y*w+x)*4, d = y*(w*4+1)+1+x*4;
      raw[d]=pixels[s]; raw[d+1]=pixels[s+1]; raw[d+2]=pixels[s+2]; raw[d+3]=pixels[s+3];
    }
  }
  const compressed = zlib.deflateSync(raw, {level:9});
  const chunks = [Buffer.from([137,80,78,71,13,10,26,10])];
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w,0); ihdr.writeUInt32BE(h,4);
  ihdr[8]=8; ihdr[9]=6;
  chunks.push(makeChunk('IHDR', ihdr));
  chunks.push(makeChunk('IDAT', compressed));
  chunks.push(makeChunk('IEND', Buffer.alloc(0)));
  return Buffer.concat(chunks);
}

function makeChunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const tb = Buffer.from(type);
  const crcBuf = Buffer.concat([tb, data]);
  const crc = Buffer.alloc(4); crc.writeInt32BE(crc32(crcBuf));
  return Buffer.concat([len, tb, data, crc]);
}

let _t = null;
function makeCRCTable() {
  if (_t) return _t;
  _t = new Int32Array(256);
  for (let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=(c&1)?(0xEDB88320^(c>>>1)):(c>>>1);_t[n]=c;}
  return _t;
}
function crc32(buf) {
  let c=0xFFFFFFFF; const t=makeCRCTable();
  for(let i=0;i<buf.length;i++) c=(c>>>8)^t[(c^buf[i])&0xFF];
  return (c^0xFFFFFFFF)|0;
}

const resDir = path.join(__dirname, 'app/src/main/res');

// Also create xxxhdpi if it doesn't exist
fs.mkdirSync(path.join(resDir, 'mipmap-xxxhdpi'), {recursive: true});

Object.entries(SIZES).forEach(([folder, size]) => {
  const dir = path.join(resDir, folder);
  fs.mkdirSync(dir, {recursive: true});
  const png = createPNG(size);
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), png);
  fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), png);
  console.log(`✓ ${folder}/ic_launcher.png (${size}x${size})`);
});

console.log('\nLauncher icons generated.');
