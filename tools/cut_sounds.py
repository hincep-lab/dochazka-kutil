"""Stříhání skutečných zvuků pro aplikaci (res/raw/*.ogg).

Zdroj: BigSoundBank.com, licence CC0 (volné dílo). Čísla souborů = ID zvuku na webu
(stránka https://bigsoundbank.com/sound-sNNNN.html, soubor /UPLOAD/ogg/NNNN.ogg).
Použití: stáhni potřebné NNNN.ogg do složky a spusť
    python3 tools/cut_sounds.py <složka_se_staženými_ogg> <výstupní_složka>
Potřebuje ffmpeg a numpy.
"""
import sys
import subprocess, numpy as np, sys, os
SR=22050
def load(p):
    raw=subprocess.run(['ffmpeg','-v','error','-i',p,'-ac','1','-ar',str(SR),'-f','f32le','-'],capture_output=True).stdout
    return np.frombuffer(raw,np.float32).copy()
def env(x,w=0.02):
    n=int(SR*w); m=len(x)//n
    return np.sqrt((x[:m*n].reshape(m,n)**2).mean(1)), n
def onset(x, k=0, thr=0.25, skip=0.0, gap=0.3):
    e,n=env(x); e=e/ (e.max()+1e-9)
    i0=int(skip*SR/n); hits=[]; i=i0
    while i<len(e):
        if e[i]>thr:
            hits.append(i*n); i+=int(gap*SR/n)
        else: i+=1
    return hits[min(k,len(hits)-1)] if hits else 0
def fade(y,fi=0.005,fo=0.08):
    a=int(fi*SR); b=int(fo*SR)
    if a: y[:a]*=np.linspace(0,1,a)
    if b: y[-b:]*=np.linspace(1,0,b)
    return y
def loop(y,xf=0.25):
    n=int(xf*SR); head=y[:n].copy(); body=y[n:].copy()
    body[-n:]=body[-n:]*np.linspace(1,0,n)+head*np.linspace(0,1,n)
    return body
def save(name,y,peak=0.7):
    y=y/ (np.abs(y).max()+1e-9)*peak
    p=f'{OUT}/{name}.ogg'
    subprocess.run(['ffmpeg','-v','error','-y','-f','f32le','-ar',str(SR),'-ac','1','-i','-','-c:a','libvorbis','-q:a','3',p],input=y.astype(np.float32).tobytes())
    print(name, round(len(y)/SR,2), os.path.getsize(p))
SRC = sys.argv[1] if len(sys.argv) > 1 else '/tmp/bsb'
OUT = sys.argv[2] if len(sys.argv) > 2 else '/tmp/snd/out'
os.makedirs(OUT, exist_ok=True)
B = SRC.rstrip('/') + '/'
def one(src,name,dur,k=0,pre=0.01,thr=0.25,skip=0.0,fo=0.08,peak=0.7):
    x=load(B+src+'.ogg'); s=max(0,onset(x,k,thr,skip)-int(pre*SR)); save(name,fade(x[s:s+int(dur*SR)].copy(),0.003,fo),peak)
def lp(src,name,a,b,peak=0.6):
    x=load(B+src+'.ogg'); save(name,loop(x[int(a*SR):int(b*SR)].copy()),peak)
one('1384','water_drip',0.25,k=2,thr=0.35,fo=0.06)
lp('0042','water_pour',1.5,5.0)
lp('1522','water_overflow',6.0,10.5)
one('1530','gag_splash',1.4,fo=0.4)
one('1519','gag_splash_big',2.4,fo=0.6)
one('3589','gag_clank',0.7,fo=0.3)
one('0206','gag_boing',1.0,fo=0.3)
one('2452','gag_thud',0.9,fo=0.2)
one('1262','gag_plop',0.45,thr=0.4)
one('1026','gag_gurgle',2.0,fo=0.5)
one('0211','gag_pop',0.6,thr=0.5,fo=0.2)
one('0033','gag_flush',4.0,thr=0.3,fo=1.0)
one('3331','gag_squeak',0.6,thr=0.3)
one('1707','gag_burp',0.15,thr=0.1,fo=0.03)
x=load(B+'0019.ogg'); y=x[int(2*SR):int(5.5*SR)].copy(); y[:int(0.4*SR)]*=np.linspace(0,1,int(0.4*SR)); save('gag_cheer',fade(y,0,0.9),0.6)
one('0297','gag_tape',1.3,thr=0.4,fo=0.2)
one('0543','gag_bucket',1.0,thr=0.4,fo=0.4)
one('0410','gag_stir',1.4,thr=0.4,fo=0.3)
one('0184','gag_drill',2.6,thr=0.3,fo=0.4)
one('1405','gag_sigh',1.6,thr=0.2,fo=0.4)
one('0445','gag_keys',1.0,thr=0.4,fo=0.3)
one('3205','gag_creak',1.2,thr=0.3,fo=0.3)
one('1774','gag_metal_fall',1.6,thr=0.3,fo=0.5)
one('1108','gag_tool_drop',1.0,thr=0.3,fo=0.3)

def loudest(src,name,dur,fo=0.3,peak=0.7,fi=0.15):
    x=load(B+src+'.ogg'); n=int(dur*SR); e=np.convolve(x**2,np.ones(2048)/2048,'same')
    c=np.cumsum(e); best=np.argmax(c[n:]-c[:-n]); y=x[best:best+n].copy()
    a=int(fi*SR); y[:a]*=np.linspace(0,1,a); save(name,fade(y,0,fo),peak)

def loudest(src,name,dur,fo=0.3,peak=0.7,fi=0.15):
    x=load(B+src+'.ogg'); n=int(dur*SR); e=np.convolve(x**2,np.ones(2048)/2048,'same')
    c=np.cumsum(e); best=np.argmax(c[n:]-c[:-n]); y=x[best:best+n].copy()
    a=int(fi*SR); y[:a]*=np.linspace(0,1,a); save(name,fade(y,0,fo),peak)
loudest('1155','gag_coffee_pour',1.6)
loudest('0170','gag_snore',1.8,fo=0.4,fi=0.2)
