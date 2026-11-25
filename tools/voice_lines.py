from TTS.api import TTS
import numpy as np, soundfile, librosa, subprocess
d='/tmp/tts/tts_models--cs--cv--vits/'
t=TTS(model_path=d+'model_file.pth.tar', config_path=d+'config.json')
sr=t.synthesizer.output_sample_rate
L={
 'voice_w1':"Co po mně chceš?", 'voice_w2':"Nevidíš, že makám?", 'voice_w3':"Hm? Co je?", 'voice_w4':"Šéfe, já fakt pracuju!",
 'voice_w5':"Klid, mám to pod kontrolou.", 'voice_w6':"Neťukej, nebo to zase praskne!", 'voice_w7':"Co čumíš? Trubka teče!",
 'voice_t1':"Ruším? Hraje se hokej!", 'voice_t2':"Co je? Teď dávaj gól!", 'voice_t3':"Po šichtě mám klid, ne?", 'voice_t4':"Pivo si nedám vzít.",
 'voice_s1':"Chrr… co?! Je noc!", 'voice_s2':"Nech mě spát…", 'voice_s3':"Zítra, zítra…",
 'voice_a1':"Zase ty?!", 'voice_a2':"No co pořád ťukáš?!", 'voice_a3':"Já ti ťuknu!", 'voice_a4':"Dost! Jdu si stěžovat!",
 'voice_e1':"Padla je padla!",
}
for k,v in L.items():
    txt=v.replace('…','.').replace('?!','!').replace('Chrr.','Chrrr,')
    w=np.array(t.tts(text=txt),dtype=np.float32)
    w=librosa.effects.pitch_shift(w, sr=sr, n_steps=-5.5)
    w,_=librosa.effects.trim(w, top_db=35)
    w=w/ (np.abs(w).max()+1e-9)*0.8
    soundfile.write(f'/tmp/tts/out/{k}.wav', w, sr)
    subprocess.run(['ffmpeg','-v','error','-y','-i',f'/tmp/tts/out/{k}.wav','-ac','1','-ar','22050','-c:a','libvorbis','-q:a','3',f'/tmp/tts/out/{k}.ogg'])
    print(k, round(len(w)/sr,2))
