// Builds www/ (the web content packaged into the APK) from the original scanner page.
// The original HTML is never modified: it is copied, a few exact strings are patched for the
// app build, and the native bridge script and offline fonts are added alongside it.
//
//   node scripts/build-web.mjs [path/to/obd-scanner.html]
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(process.argv[2] ?? join(root, '..', 'obd-scanner (1).html'));
const out = join(root, 'www');

let html = readFileSync(source, 'utf8');

/** Replace `find` (which must occur exactly once) so a changed source page fails loudly instead of silently. */
function patch(what, find, replacement) {
  const count = html.split(find).length - 1;
  if (count !== 1) throw new Error(`Patch "${what}": expected 1 match in ${source}, found ${count}`);
  html = html.replace(find, () => replacement);
}

// Fonts: packaged locally so the app looks right with no internet (in a car, usually the case).
patch('font preconnect', '<link rel="preconnect" href="https://fonts.googleapis.com">\n', '');
patch(
  'font stylesheet',
  /<link href="https:\/\/fonts\.googleapis\.com\/css2[^"]*" rel="stylesheet">/.exec(html)?.[0] ?? '<google fonts link>',
  '<link href="fonts/fonts.css" rel="stylesheet">\n<script src="native-shim.js"></script>'
);

// Connection check: inside the app the WebView is expected, and Bluetooth comes from native code.
patch('webview check', 'i=/; wv\\)|Version\\/\\d+\\.\\d+ Chrome/i.test(t)', 'i=!window.ObdNative&&/; wv\\)|Version\\/\\d+\\.\\d+ Chrome/i.test(t)');
patch('permissions policy', 'let c=document.permissionsPolicy||document.featurePolicy', 'let c=window.ObdNative?null:document.permissionsPolicy||document.featurePolicy');
patch('ble picker wording', 'Choose your adapter in the browser\\u2019s list\\u2026', 'Choose your adapter from the list\\u2026');

// Wi-Fi adapters: a transport class, a button on the Connect screen, its click handler, and reconnect support.
patch(
  'wifi transport',
  'var B=pe;',
  'var B=pe;var WF=class extends U{constructor(n=!1){super();this.again=n;this.kind="wifi";this.label="Wi\\u2011Fi adapter";this.h=null}' +
    'async connect(){this.h=await window.ObdNative.openWifi(this.again,n=>this.dataCb(n),n=>this.closeCb(n||"Wi\\u2011Fi link closed"));this.label=this.h.label}' +
    'async write(n){if(!this.h)throw new Error("Not connected");await this.h.write(n)}' +
    'async disconnect(){let n=this.h;this.h=null;n&&await n.close()}};'
);
patch(
  'wifi button',
  '${n("c-demo","Demo car"',
  '${window.ObdNative?n("c-wifi","Wi\\u2011Fi adapter","Join the adapter\\u2019s Wi\\u2011Fi network first. Usually 192.168.0.10:35000.",!0,' +
    '\'<path d="M2 8.8a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16.2a5 5 0 0 1 7 0M12 20h.01"/>\'):""}\n    ${n("c-demo","Demo car"'
);
patch(
  'wifi notice',
  '<p class="dim">Wi\\u2011Fi ELM327 adapters can\\u2019t be reached from a web page; use the native app build for those.</p>',
  '${window.ObdNative?"":"<p class=\\"dim\\">Wi\\u2011Fi ELM327 adapters can\\u2019t be reached from a web page; use the native app build for those.</p>"}'
);
patch(
  'wifi click',
  'f("c-ser")?.addEventListener("click",()=>he(new B)),',
  'f("c-ser")?.addEventListener("click",()=>he(new B)),f("c-wifi")?.addEventListener("click",()=>he(new WF)),'
);
patch('wifi reconnect', 'e instanceof V?new V:null', 'e instanceof V?new V:e instanceof WF?new WF(!0):null');

// Trouble codes: tapping a code opens a sheet that offers a Google search for it. The link is a plain
// external <a>, which the app hands to the phone's browser and a desktop browser opens in a new tab.
patch(
  'lookup styles',
  '.code p{font-size:15px;line-height:21px}',
  '.code p{font-size:15px;line-height:21px}.code[data-dtc]{cursor:pointer}.code[data-dtc]:active{opacity:.7}' +
    'a.btn{display:flex;align-items:center;justify-content:center;text-decoration:none;box-sizing:border-box}'
);
patch(
  'lookup card',
  '<div class="card code">',
  '<div class="card code" role="button" tabindex="0" data-dtc="${x(r)}" data-desc="${x(Ct[r]??"")}">'
);
patch(
  'lookup sheet',
  'function Ee(e,t,n){',
  'function LK(e,t){let n=document.createElement("div");n.className="sheet-bg";' +
    'let r="https://www.google.com/search?q="+encodeURIComponent(e+" OBD2 code"+(t?" "+t:""));' +
    'n.innerHTML=`<div class="sheet" role="dialog" aria-modal="true" aria-labelledby="lk-t"><div class="grab"></div>' +
    '<h2 id="lk-t">${x(e)}</h2><p>${x(t||"No description in the local database.")}</p>' +
    '<a class="btn" id="lk-go" href="${x(r)}" target="_blank" rel="noopener noreferrer">Search on Google</a>' +
    '<button class="btn ghost" id="lk-no">Close</button></div>`;document.body.appendChild(n);' +
    'n.querySelector("#lk-go").addEventListener("click",()=>setTimeout(()=>n.remove(),0));' +
    'n.querySelector("#lk-no").addEventListener("click",()=>n.remove());' +
    'n.addEventListener("click",i=>{i.target===n&&n.remove()});n.querySelector("#lk-go").focus()}' +
    'function Ee(e,t,n){'
);
patch(
  'lookup click',
  'f("read")?.addEventListener("click",()=>Be()),',
  'document.querySelectorAll("[data-dtc]").forEach(e=>{let t=()=>LK(e.dataset.dtc,e.dataset.desc);e.addEventListener("click",t),' +
    'e.addEventListener("keydown",n=>{(n.key==="Enter"||n.key===" ")&&(n.preventDefault(),t())})}),' +
    'f("read")?.addEventListener("click",()=>Be()),'
);

// ---- Sensors: find every sensor the car has, and keep each one updating.

// Detection. Ask every "supported PIDs" range instead of stopping at the first one whose continuation bit is
// clear (some ECUs break the chain), and merge the answers of all ECUs instead of using only the first reply
// (otherwise a transmission ECU answering first hides the engine's sensors).
patch(
  'sensor detection',
  'async readSupported(t){let n=[],r=t;for(let i=0;i<=160;i+=32){i>0&&(r=await this.send("01"+S(i),5e3).catch(()=>"NO DATA"));let a=X(r,i);if(!a)break;let c=Ie(i,a);if(n=n.concat(c),!c.includes(i+32))break}return n}',
  'async readSupported(t){let n=new Set,r=t;for(let i=0;i<=192;i+=32){if(i>0&&(r=await this.send("01"+S(i),5e3).catch(()=>null)),r===null)break;' +
    'for(let a of ne(r))if(a[0]===65&&a[1]===i&&a.length>=6)for(let c of Ie(i,a.slice(2)))n.add(c)}return Array.from(n).sort((i,a)=>i-a)}'
);

// Batched replies are split using each sensor's byte length. Only accept the sensors that were asked for, so
// one unexpected length cannot be misread as values for unrelated sensors.
patch(
  'batch parsing',
  'r=await this.send("01"+n.map(S).join(""),6e3);return Ue(r,F)',
  'r=await this.send("01"+n.map(S).join(""),6e3);return Ue(r,new Map(n.map(i=>[i,F.get(i)])))'
);

// PID 7C (DPF temperature) is 9 bytes: a support byte, then inlet and outlet temperatures. It was decoded as
// 2 bytes starting at the support byte. No fixed length is declared, so it is read on its own, not batched.
patch(
  'dpf temperature',
  'o(124,"Diesel particulate filter temperature","DPF temp","Temperatures",{unit:"\\xB0C",min:0,max:1e3,decimals:0,bytes:2,decode:e=>v(e)/10-40})',
  'o(124,"Diesel particulate filter inlet temperature","DPF inlet","Temperatures",{unit:"\\xB0C",min:0,max:1e3,decimals:0,decode:e=>(R(e)*256+ve(e))/10-40,valid:e=>(h(e)&1)===1}),' +
    'o(124,"Diesel particulate filter outlet temperature","DPF outlet","Temperatures",{unit:"\\xB0C",min:0,max:1e3,decimals:0,decode:e=>(We(e)*256+(e[4]??0))/10-40,valid:e=>(h(e)&2)===2},"7Co")'
);

// PIDs 78/79 (exhaust gas temperature, bank 1/2): a support byte, then four 2-byte temperatures.
patch(
  'exhaust gas temperature',
  'var se=new Map;',
  'for(let[e,t]of[[120,"B1"],[121,"B2"]])for(let n=0;n<4;n++)N.push(o(e,`Exhaust gas temperature ${t} sensor ${n+1}`,`EGT ${t}S${n+1}`,"Temperatures",' +
    '{unit:"\\xB0C",min:0,max:1e3,decimals:0,decode:r=>((r[1+2*n]??0)*256+(r[2+2*n]??0))/10-40,valid:r=>(h(r)>>n&1)===1},S(e)+(n?"s"+(n+1):"")));' +
    'var se=new Map;'
);

// Polling. A sensor that keeps going missing from batched replies is read on its own instead. A sensor that
// does not answer on its own either shows "n/a" and is only retried now and then, so it cannot slow the rest.
patch(
  'sensor fallback',
  'be=async g=>{let A=await e.readPidBytes(g);b=0,A&&(rt(g,A),y++)};',
  'ms=new Map,dd=[],di=0,pm=0,sm=!1,dl=new Set,' +
    // pk: split a list of sensors into requests whose replies each fit one CAN frame (1 + id and data bytes <= 7)
    'pk=g=>{let E=[],z=[],J=1;for(let I of g){let T=1+(F.get(I)??9);z.length&&J+T>7&&(E.push(z),z=[],J=1),z.push(I),J+=T}return z.length&&E.push(z),E},' +
    'bm=g=>{let A=(ms.get(g)??0)+1;ms.set(g,A);let E=a.indexOf(g);A>=3&&E>=0&&(a.splice(E,1),c.push(g),ms.delete(g))},' +
    'be=async g=>{let A=await e.readPidBytes(g);if(b=0,A){rt(g,A),y++,ms.delete(g);dd.includes(g)&&(c.push(...dd.splice(0)),ms.clear())}' +
    'else{let E=(ms.get(g)??0)+1;ms.set(g,E);let z=a.includes(g)?a:c,J=z.indexOf(g);if(E>=4&&J>=0){z.splice(J,1),dd.push(g),ms.delete(g);' +
    'for(let I of Q.get(g)??[we(g)])s.values[I.key]||(s.values[I.key]={v:NaN,text:"n/a",min:NaN,max:NaN});' +
    'dl.has(g)||(dl.add(g),_("info","Sensor 01"+S(g)+" is listed by the car but does not answer"))}}};'
);
// The polling cycle. Three request styles, tried in this order:
//   full   one request for up to 6 sensors. The reply spans several CAN frames.
//   short  requests sized so each reply fits one CAN frame (7 bytes). Used once full replies keep coming back
//          incomplete: typically two ECUs answering at once, whose multi-frame replies get interleaved and
//          cannot be told apart, or an adapter that mishandles multi-frame replies.
//   single one sensor per request (non-CAN cars, or ECUs that ignore multi-sensor requests).
patch(
  'poll cycle',
  'let g=[...r],A=[];if(s.tab==="gauge")d.length&&g.push(d[u++%d.length]);else{let E=s.info?.isCan&&!s.multiOff?4:1;for(let z=0;z<Math.min(E,a.length);z++)g.push(a[l++%a.length]);c.length&&A.push(c[m++%c.length])}' +
    'try{if(s.info?.isCan&&!s.multiOff&&g.length>1){let E=await e.readPidsMulti(g);b=0;for(let[J,mt]of E)rt(J,mt),y++;let z=g.filter(J=>!E.has(J));' +
    'if(z.length&&E.size<=1){++K>=3&&(s.multiOff=!0,_("info","Batched requests not supported here; reading one sensor at a time"));for(let J of z){if(s.elm!==e)return;await be(J)}}else K=0}' +
    'else for(let E of g){if(s.elm!==e)return;await be(E)}for(let E of A){if(s.elm!==e)return;await be(E)}}catch{',
  'let g=[...r],A=[],bt=s.info?.isCan&&!s.multiOff;if(s.tab==="gauge")d.length&&g.push(d[u++%d.length]);' +
    'else{let E=bt?sm?6:4:3;for(let z=0;z<Math.min(E,a.length);z++)g.push(a[l++%a.length]);c.length&&A.push(c[m++%c.length]),dd.length&&w%5===0&&A.push(dd[di++%dd.length])}' +
    'try{for(let G of bt?sm?pk(g):[g]:g.map(E=>[E])){if(s.elm!==e)return;' +
    'if(s.info?.isCan&&!s.multiOff&&G.length>1){let E=await e.readPidsMulti(G);b=0;for(let[J,mt]of E)rt(J,mt),y++,ms.delete(J);let z=G.filter(J=>!E.has(J));' +
    'if(z.length&&E.size<=1&&(G.length>2||!sm)){++K>=3&&(s.multiOff=!0,_("info","Batched requests not supported here; reading one sensor at a time"));for(let J of z){if(s.elm!==e)return;await be(J)}}' +
    'else if(z.length){K=0;for(let J of z){if(s.elm!==e)return;a.includes(J)?bm(J):await be(J)}' +
    '!sm&&++pm>=3&&(sm=!0,_("info","Batched replies keep coming back incomplete; switching to short requests"))}' +
    'else K=0,pm=0}else for(let E of G){if(s.elm!==e)return;await be(E)}}' +
    'for(let E of A){if(s.elm!==e)return;await be(E)}}catch{'
);

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'fonts'), { recursive: true });
writeFileSync(join(out, 'index.html'), html);
copyFileSync(join(root, 'src', 'native-shim.js'), join(out, 'native-shim.js'));

const fonts = [
  ['Inter', 'inter'],
  ['Orbitron', 'orbitron'],
  ['JetBrains Mono', 'jetbrains-mono']
];
let css = '';
for (const [family, pkg] of fonts) {
  const file = `${pkg}-latin-wght-normal.woff2`;
  copyFileSync(join(root, 'node_modules', '@fontsource-variable', pkg, 'files', file), join(out, 'fonts', file));
  css += `@font-face{font-family:'${family}';font-style:normal;font-weight:100 900;font-display:swap;src:url(${file}) format('woff2')}\n`;
}
writeFileSync(join(out, 'fonts', 'fonts.css'), css);

console.log(`Built ${out} from ${source}`);
