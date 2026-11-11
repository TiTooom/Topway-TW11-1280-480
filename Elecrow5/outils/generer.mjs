// Génère les ressources de l'accueil façon Sandero pour Elecrow5 : polices Roboto (avec
// accents), icônes (redessinées d'après Icon.java de Sandero) et voiture (photo de Sandero,
// halo et ombre calculés comme dans CarView.java).
//
// Usage : cd Elecrow5/outils && npm install && node generer.mjs
// Sorties : ../src/ressources/*.c et ressources.h (fichiers générés, à ne pas retoucher).
// Polices : outils/polices/Roboto-*.ttf (Apache 2.0, github.com/googlefonts/roboto-2).

import { createCanvas, loadImage } from '@napi-rs/canvas';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ICI = dirname(fileURLToPath(import.meta.url));
const SORTIE = join(ICI, '..', 'src', 'ressources');
const PHOTO = join(ICI, '..', '..', 'SanderoLauncher', 'app', 'src', 'main', 'res', 'drawable-nodpi', 'car_photo.png');
mkdirSync(SORTIE, { recursive: true });

const declarations = [];

// --- Polices ------------------------------------------------------------------------
// Latin-1 complet (accents français), plus ’ … – Œ œ €.
const LATIN = '0x20-0x7E,0xA0-0xFF,0x2019,0x2026,0x2013,0x152,0x153,0x20AC';
const POLICES = [
    ['roboto_thin_96', 'Thin', 96, '0x30-0x3A,0x20,0x2D'],   // heure : chiffres, « : », « - »
    ['roboto_regular_12', 'Regular', 12, LATIN],
    ['roboto_regular_15', 'Regular', 15, LATIN],
    ['roboto_regular_16', 'Regular', 16, LATIN],
    ['roboto_regular_17', 'Regular', 17, LATIN],
    ['roboto_regular_18', 'Regular', 18, LATIN],
    ['roboto_regular_23', 'Regular', 23, LATIN],
    ['roboto_medium_16', 'Medium', 16, LATIN],
    ['roboto_medium_36', 'Medium', 36, LATIN],
];
const CONV = join(ICI, 'node_modules', 'lv_font_conv', 'lv_font_conv.js');
for (const [nom, graisse, taille, plage] of POLICES) {
    const ttf = join(ICI, 'polices', `Roboto-${graisse}.ttf`);
    if (!existsSync(ttf)) throw new Error(`police absente : ${ttf}`);
    execFileSync(process.execPath, [CONV, '--bpp', '4', '--size', String(taille), '--no-compress',
        '--font', ttf, '--range', plage, '--format', 'lvgl', '--lv-include', 'lvgl.h',
        '-o', join(SORTIE, `${nom}.c`)], { stdio: 'inherit' });
    declarations.push(`LV_FONT_DECLARE(${nom});`);
}

// --- Conversion en images LVGL ----------------------------------------------------------
const images = [];   // { nom, cf, w, h, octets }

function hex(octets) {
    const lignes = [];
    for (let i = 0; i < octets.length; i += 24) {
        lignes.push('    ' + Array.from(octets.subarray(i, i + 24), (v) => '0x' + v.toString(16).padStart(2, '0')).join(','));
    }
    return lignes.join(',\n');
}

/** Rectangle englobant des points visibles (alpha > 0). */
function cadre(data, w, h) {
    let x0 = w, y0 = h, x1 = -1, y1 = -1;
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        if (data[(y * w + x) * 4 + 3] > 0) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
        }
    }
    return x1 < 0 ? { x: 0, y: 0, w: 1, h: 1 } : { x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1 };
}

/** Image A8 (masque) : couleur donnée à l'affichage (image_recolor). */
function versA8(nom, ctx, r) {
    const d = ctx.getImageData(r.x, r.y, r.w, r.h).data;
    const a = new Uint8Array(r.w * r.h);
    for (let i = 0; i < a.length; i++) a[i] = d[i * 4 + 3];
    images.push({ nom, cf: 'LV_COLOR_FORMAT_A8', w: r.w, h: r.h, stride: r.w, octets: a });
}

/** Image RGB565A8 : plan RGB565 (petit-boutiste) puis plan alpha. */
function versRGB565A8(nom, ctx, r) {
    const d = ctx.getImageData(r.x, r.y, r.w, r.h).data;
    const n = r.w * r.h;
    const o = new Uint8Array(n * 3);
    for (let i = 0; i < n; i++) {
        const R = d[i * 4], G = d[i * 4 + 1], B = d[i * 4 + 2];
        const v = ((R >> 3) << 11) | ((G >> 2) << 5) | (B >> 3);
        o[i * 2] = v & 0xff;
        o[i * 2 + 1] = v >> 8;
        o[n * 2 + i] = d[i * 4 + 3];
    }
    images.push({ nom, cf: 'LV_COLOR_FORMAT_RGB565A8', w: r.w, h: r.h, stride: r.w * 2, octets: o });
}

// --- Icônes (grille 24 x 24 d'Icon.java, trait 2, bouts et jonctions arrondis) -----------
const ICONES = {
    play(c) { poly(c, 7.5, 4.5, 19.5, 12, 7.5, 19.5); c.fill(); },
    pause(c) { rr(c, 6, 5, 10, 19, 1.3); c.fill(); rr(c, 14, 5, 18, 19, 1.3); c.fill(); },
    prev(c) { rr(c, 5, 5.5, 7.5, 18.5, 1); c.fill(); poly(c, 19, 5.5, 8.5, 12, 19, 18.5); c.fill(); },
    next(c) { rr(c, 16.5, 5.5, 19, 18.5, 1); c.fill(); poly(c, 5, 5.5, 15.5, 12, 5, 18.5); c.fill(); },
    stop(c) { rr(c, 6, 6, 18, 18, 2.2); c.fill(); },
    vol_down(c) { haut_parleur(c); ligne(c, 15.5, 12, 21, 12); },
    vol_up(c) { haut_parleur(c); ligne(c, 15.5, 12, 21, 12); ligne(c, 18.25, 9.25, 18.25, 14.75); },
    radio(c) {
        rr(c, 3, 8, 21, 20, 2.5); c.stroke();
        ligne(c, 7, 8, 16, 3.5);
        rond(c, 15.5, 14, 2.6); c.fill();
        ligne(c, 6.5, 12, 10, 12); ligne(c, 6.5, 16, 10, 16);
    },
    bt(c) {
        c.beginPath(); c.moveTo(7, 7.5); c.lineTo(17, 16.5); c.lineTo(12, 21); c.lineTo(12, 3);
        c.lineTo(17, 7.5); c.lineTo(7, 16.5); c.stroke();
    },
    settings(c) {
        for (let i = 0; i < 8; i++) {
            c.save(); c.translate(12, 12); c.rotate(i * Math.PI / 4); c.translate(-12, -12);
            rr(c, 10.4, 2.2, 13.6, 6.5, 0.8); c.fill(); c.restore();
        }
        c.lineWidth = 3.6; rond(c, 12, 12, 5.4); c.stroke(); c.lineWidth = 2;
    },
    apps(c) { for (let x = 0; x < 3; x++) for (let y = 0; y < 3; y++) { rond(c, 6 + x * 6, 6 + y * 6, 1.9); c.fill(); } },
    rew(c) { poly(c, 11.5, 6, 3, 12, 11.5, 18); c.fill(); poly(c, 20.5, 6, 12, 12, 20.5, 18); c.fill(); },
    fwd(c) { poly(c, 3.5, 6, 12, 12, 3.5, 18); c.fill(); poly(c, 12.5, 6, 21, 12, 12.5, 18); c.fill(); },
    scan(c) { rond(c, 10.5, 10.5, 6); c.stroke(); ligne(c, 15, 15, 20, 20); ligne(c, 8, 10.5, 13, 10.5); },
    power(c) {
        // Arc de -60° sur 300° (sens horaire, comme Canvas.drawArc d'Android).
        c.beginPath(); c.ellipse(12, 12.5, 7, 7, 0, -60 * Math.PI / 180, 240 * Math.PI / 180); c.stroke();
        ligne(c, 12, 3.5, 12, 11.5);
    },
    open(c) {
        c.beginPath(); c.moveTo(11, 5); c.lineTo(6.5, 5); c.quadraticCurveTo(5, 5, 5, 6.5); c.lineTo(5, 17.5);
        c.quadraticCurveTo(5, 19, 6.5, 19); c.lineTo(17.5, 19); c.quadraticCurveTo(19, 19, 19, 17.5); c.lineTo(19, 13);
        c.stroke();
        ligne(c, 11, 13, 19, 5); ligne(c, 13.5, 5, 19, 5); ligne(c, 19, 5, 19, 10.5);
    },
};
function poly(c, ...xy) { c.beginPath(); c.moveTo(xy[0], xy[1]); for (let i = 2; i < xy.length; i += 2) c.lineTo(xy[i], xy[i + 1]); c.closePath(); }
function rr(c, l, t, r, b, k) { c.beginPath(); c.roundRect(l, t, r - l, b - t, k); }
function rond(c, x, y, r) { c.beginPath(); c.arc(x, y, r, 0, Math.PI * 2); }
function ligne(c, a, b, x, y) { c.beginPath(); c.moveTo(a, b); c.lineTo(x, y); c.stroke(); }
function haut_parleur(c) { poly(c, 3, 9.5, 7, 9.5, 12, 5, 12, 19, 7, 14.5, 3, 14.5); c.fill(); }

// Icônes et tailles utilisées par l'accueil (voir interface.c).
const TAILLES = {
    20: ['radio', 'bt'],                               // pastilles Radio / Bluetooth
    72: ['radio', 'bt'],                               // vignette de la source
    24: ['stop', 'scan', 'open', 'radio', 'vol_up', 'vol_down'],  // petits boutons, volume
    30: ['prev', 'next', 'rew', 'fwd'],                // boutons moyens
    40: ['play', 'pause', 'power'],                    // grand bouton
    22: ['settings', 'apps'],                          // dock
};
for (const [taille, noms] of Object.entries(TAILLES)) {
    for (const nom of noms) {
        const t = Number(taille);
        const cv = createCanvas(t, t);
        const c = cv.getContext('2d');
        c.fillStyle = '#fff'; c.strokeStyle = '#fff';
        c.lineWidth = 2; c.lineCap = 'round'; c.lineJoin = 'round';
        c.scale(t / 24, t / 24);
        ICONES[nom](c);
        versA8(`icone_${nom}_${t}`, c, { x: 0, y: 0, w: t, h: t });
    }
}

// --- Voiture (CarView.java, photo détourée, avant à gauche) ------------------------------
// Zone de la voiture dans la colonne de gauche (interface.c) : VOITURE_L x VOITURE_H.
const VOITURE_L = 280, VOITURE_H = 232;
const photo = await loadImage(PHOTO);
const spec = {
    rearX: 0.863, rearY: 0.638, frontX: 0.320, frontY: 0.792,
    nearX: 0.717, nearY: 0.738, farX: 0.466, farY: 0.691, shadow: 0.45, placeX: 0.6,
};
const s = Math.min(VOITURE_L * 0.94 / photo.width, VOITURE_H / photo.height);
const bw = photo.width * s, bh = photo.height * s;
const gauche = (VOITURE_L - bw) * spec.placeX, haut = (VOITURE_H - bh) / 2;
const X = (f) => gauche + f * bw, Y = (f) => haut + f * bh;
const cx = (X(spec.rearX) + X(spec.frontX) + X(spec.nearX) + X(spec.farX)) / 4;
const cy = (Y(spec.rearY) + Y(spec.frontY) + Y(spec.nearY) + Y(spec.farY)) / 4;
const angle = Math.atan2(Y(spec.frontY) - Y(spec.rearY), X(spec.frontX) - X(spec.rearX));
const rx = Math.hypot(X(spec.frontX) - X(spec.rearX), Y(spec.frontY) - Y(spec.rearY)) / 2;
const ry = Math.hypot(X(spec.nearX) - X(spec.farX), Y(spec.nearY) - Y(spec.farY)) / 2;

function ellipse_sol(c, kx, ky, arrets) {
    c.save();
    c.translate(cx, cy); c.rotate(angle); c.scale(rx * kx, ry * ky);
    const g = c.createRadialGradient(0, 0, 0, 0, 0, 1);
    for (const [p, coul] of arrets) g.addColorStop(p, coul);
    c.fillStyle = g;
    c.beginPath(); c.arc(0, 0, 1, 0, Math.PI * 2); c.fill();
    c.restore();
}

// Halo : en blanc (seul l'alpha compte), recoloré à l'accent de la source par LVGL.
{
    const cv = createCanvas(VOITURE_L, VOITURE_H);
    const c = cv.getContext('2d');
    ellipse_sol(c, 1.25, 2.2, [[0, 'rgba(255,255,255,0.6)'], [0.5, 'rgba(255,255,255,0.2)'], [1, 'rgba(255,255,255,0)']]);
    const r = cadre(c.getImageData(0, 0, VOITURE_L, VOITURE_H).data, VOITURE_L, VOITURE_H);
    versA8('voiture_halo', c, r);
    declarations.push(`#define VOITURE_HALO_X ${r.x}\n#define VOITURE_HALO_Y ${r.y}`);
}
// Ombre de contact puis photo (reflet compris).
{
    const cv = createCanvas(VOITURE_L, VOITURE_H);
    const c = cv.getContext('2d');
    const sa = Math.round(0xCC * spec.shadow) / 255, sb = Math.round(0x66 * spec.shadow) / 255;
    ellipse_sol(c, 1.08, 1.25, [[0, `rgba(0,0,0,${sa})`], [0.55, `rgba(0,0,0,${sb})`], [1, 'rgba(0,0,0,0)']]);
    c.imageSmoothingQuality = 'high';
    c.drawImage(photo, gauche, haut, bw, bh);
    const r = cadre(c.getImageData(0, 0, VOITURE_L, VOITURE_H).data, VOITURE_L, VOITURE_H);
    versRGB565A8('voiture', c, r);
    declarations.push(`#define VOITURE_X ${r.x}\n#define VOITURE_Y ${r.y}`);
}
declarations.push(`#define VOITURE_L ${VOITURE_L}\n#define VOITURE_H ${VOITURE_H}`);

// --- Écriture ------------------------------------------------------------------------
let c = '// Fichier GENERE par outils/generer.mjs : ne pas modifier a la main.\n#include "ressources.h"\n\n';
for (const im of images) {
    c += `static const uint8_t ${im.nom}_data[] = {\n${hex(im.octets)}\n};\n`;
    c += `const lv_image_dsc_t ${im.nom} = {\n` +
        `    .header = { .magic = LV_IMAGE_HEADER_MAGIC, .cf = ${im.cf}, .w = ${im.w}, .h = ${im.h}, .stride = ${im.stride} },\n` +
        `    .data_size = sizeof(${im.nom}_data),\n    .data = ${im.nom}_data,\n};\n\n`;
    declarations.push(`LV_IMAGE_DECLARE(${im.nom});`);
}
writeFileSync(join(SORTIE, 'images.c'), c);

writeFileSync(join(SORTIE, 'ressources.h'),
    '// Fichier GENERE par outils/generer.mjs : ne pas modifier a la main.\n#pragma once\n#include "lvgl.h"\n\n' +
    declarations.join('\n') + '\n');

console.log(`${POLICES.length} polices, ${images.length} images -> ${SORTIE}`);
