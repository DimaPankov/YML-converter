#!/usr/bin/env python3
"""Build a Windows installer on macOS, Linux or Windows using NSIS."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERSION = '1.0.0'
PYTHON_VERSION = '3.13.13'
RUNTIME_URL = f'https://www.python.org/ftp/python/{PYTHON_VERSION}/python-{PYTHON_VERSION}-embed-amd64.zip'


def write_icon(path):
    # Code-drawn logo matching the UI; multiple BMP sizes for Windows shortcuts.
    images=[]
    glyph=['10001','10001','10001','01111','00001','10001','01110']
    for size in (16,32,48,64):
        pixels=bytearray();mask=bytearray()
        for y in reversed(range(size)):
            maskrow=0
            for x in range(size):
                radius=size*.20
                dx=max(radius-x,0,x-(size-1-radius));dy=max(radius-y,0,y-(size-1-radius))
                inside=dx*dx+dy*dy<=radius*radius
                gx=int((x-size*.18)/(size*.10));gy=int((y-size*.17)/(size*.095))
                letter=(size*.18<=x<size*.68 and size*.17<=y<size*.835 and 0<=gx<5 and 0<=gy<7 and glyph[gy][gx]=='1')
                dot=(x-size*.78)**2+(y-size*.76)**2<(size*.065)**2
                b,g,r=(255,255,255) if letter or dot else (80,108,35)
                pixels+=bytes((b,g,r,255 if inside else 0))
                maskrow=(maskrow<<1)|(0 if inside else 1)
            pad=((size+31)//32)*4
            mask+= (maskrow << (pad*8-size)).to_bytes(pad,'big')
        header=struct.pack('<IiiHHIIiiII',40,size,size*2,1,32,0,len(pixels),0,0,0,0)
        images.append((size,header+pixels+mask))
    offset=6+16*len(images);entries=[]
    for size,raw in images:
        entries.append(struct.pack('<BBBBHHII',size,size,0,0,1,32,len(raw),offset));offset+=len(raw)
    path.write_bytes(struct.pack('<HHH',0,1,len(images))+b''.join(entries)+b''.join(raw for _,raw in images))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--runtime',type=Path,default=ROOT/'build/downloads'/f'python-{PYTHON_VERSION}-embed-amd64.zip')
    args=parser.parse_args()
    if not args.runtime.is_file():
        raise SystemExit('Download the official runtime first: '+RUNTIME_URL)
    makensis=shutil.which('makensis')
    if not makensis: raise SystemExit('NSIS makensis is required to build the installer.')
    stage=ROOT/'build/windows/payload'
    stage.mkdir(parents=True,exist_ok=True)
    runtime=stage/'runtime';runtime.mkdir(exist_ok=True)
    with zipfile.ZipFile(args.runtime) as z:
        if z.testzip() is not None: raise SystemExit('Corrupt runtime archive')
        for name in z.namelist():
            if '/' in name or '\\' in name or name in ('.','..'): raise SystemExit('Unexpected runtime path: '+name)
        z.extractall(runtime)
    (runtime/'python313._pth').write_text('python313.zip\n.\n..\n',encoding='ascii')
    for name in ('desktop.py','server.py','catalog.py','README.md'):
        shutil.copy2(ROOT/name,stage/name)
    shutil.copytree(ROOT/'static',stage/'static',dirs_exist_ok=True)
    shutil.copy2(Path(__file__).parent/'Инструкция.txt',stage/'Инструкция Windows.txt')
    write_icon(stage/'app.ico')
    manifest={'version':VERSION,'python':PYTHON_VERSION,'runtime_url':RUNTIME_URL,'runtime_sha256':hashlib.sha256(args.runtime.read_bytes()).hexdigest(),'files':{}}
    for file in sorted(stage.rglob('*')):
        if file.is_file() and file.name!='build-manifest.json':
            manifest['files'][file.relative_to(stage).as_posix()]=hashlib.sha256(file.read_bytes()).hexdigest()
    (stage/'build-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    dist=ROOT/'dist';dist.mkdir(exist_ok=True)
    output=dist/f'YML-Studio-Setup-{VERSION}-x64.exe'
    size=sum(p.stat().st_size for p in stage.rglob('*') if p.is_file())//1024+1024
    prefix='/' if os.name=='nt' else '-'
    subprocess.run([makensis,prefix+'V3',prefix+'DAPP_SOURCE='+str(stage),prefix+'DOUTPUT_FILE='+str(output),prefix+'DAPP_ICON='+str(stage/'app.ico'),prefix+'DAPP_SIZE_KB='+str(size),str(Path(__file__).parent/'installer.nsi')],check=True,env={**os.environ,'LC_ALL':'en_US.UTF-8'})
    digest=hashlib.sha256(output.read_bytes()).hexdigest()
    (dist/(output.name+'.sha256')).write_text(digest+'  '+output.name+'\n','ascii')
    shutil.copy2(Path(__file__).parent/'Инструкция.txt',dist/'Инструкция Windows.txt')
    print(f'Built {output} ({output.stat().st_size:,} bytes)\nSHA256 {digest}')


if __name__=='__main__': main()
