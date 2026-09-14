#!/usr/bin/env python3
"""Dependency-free local application. Bind only to loopback."""
import argparse, base64, io, json, os, struct, threading, uuid, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from catalog import default_state, check_shape, validate, build_yml, FIELDS

ROOT=Path(__file__).resolve().parent
DATA=ROOT/'data'
LOCK=threading.Lock()
MAX_BODY=32*1024*1024

def image_info(data):
    if data.startswith(b'\x89PNG\r\n\x1a\n') and len(data)>=33 and data[12:16]==b'IHDR':
        w,h=struct.unpack('>II',data[16:24]); ext='png'
    elif data.startswith(b'\xff\xd8'):
        i=2; w=h=0; ext='jpg'
        while i<len(data):
            if data[i]!=255: raise ValueError('Повреждён JPEG')
            while i<len(data) and data[i]==255: i+=1
            if i>=len(data): break
            marker=data[i]; i+=1
            if marker in (0xd9,0xda): break
            if marker in range(0xd0,0xd9) or marker==1: continue
            if i+2>len(data): break
            length=int.from_bytes(data[i:i+2],'big')
            if length<2 or i+length>len(data): raise ValueError('Повреждён JPEG')
            if marker in (0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf) and length>=8:
                h,w=struct.unpack('>HH',data[i+3:i+7]); break
            i+=length
        if not w or not h: raise ValueError('Не удалось определить размеры JPEG')
    else: raise ValueError('Допустимы только JPEG и PNG')
    if min(w,h)<250 or max(w,h)>3500: raise ValueError('Размеры изображения должны быть от 250 до 3500 пикселей по каждой стороне')
    return ext,w,h

def read_state():
    path=DATA/'project.json'
    return json.loads(path.read_text('utf-8')) if path.exists() else default_state()

def save_state(state):
    check_shape(state)
    temp=DATA/'project.tmp'
    temp.write_text(json.dumps(state,ensure_ascii=False,indent=2),'utf-8')
    os.replace(temp,DATA/'project.json')

class Handler(BaseHTTPRequestHandler):
    def send(self,status,data,ctype='application/json; charset=utf-8',filename=None):
        if not isinstance(data,bytes): data=json.dumps(data,ensure_ascii=False).encode('utf-8')
        self.send_response(status); self.send_header('Content-Type',ctype)
        self.send_header('Content-Length',str(len(data))); self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Cache-Control','no-store')
        if filename: self.send_header('Content-Disposition','attachment; filename="'+filename+'"')
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        path=urlsplit(self.path).path
        try:
            if path=='/api/state':
                with LOCK: state=read_state()
                return self.send(200,{'state':state,'fields':FIELDS})
            if path=='/api/backup':
                with LOCK:
                    buf=io.BytesIO()
                    with zipfile.ZipFile(buf,'w',zipfile.ZIP_DEFLATED) as z:
                        z.writestr('project.json',json.dumps(read_state(),ensure_ascii=False,indent=2))
                        for f in (DATA/'images').iterdir():
                            if f.is_file(): z.write(f,'images/'+f.name)
                return self.send(200,buf.getvalue(),'application/zip','yml-project-backup.zip')
            if path.startswith('/images/'):
                name=path.removeprefix('/images/')
                import re
                if not re.fullmatch(r'[a-f0-9]{32}\.(jpg|png)',name): return self.send(404,{'error':'Файл не найден'})
                return self.send(200,(DATA/'images'/name).read_bytes(),'image/png' if name.endswith('.png') else 'image/jpeg')
            files={'/':'index.html','/app.js':'app.js','/desktop.js':'desktop.js','/style.css':'style.css'}
            if path not in files: return self.send(404,{'error':'Не найдено'})
            file=ROOT/'static'/files[path]
            return self.send(200,file.read_bytes(),{'html':'text/html; charset=utf-8','js':'text/javascript; charset=utf-8','css':'text/css; charset=utf-8'}[file.suffix[1:]])
        except FileNotFoundError: self.send(404,{'error':'Файл не найден'})
        except Exception as e: self.send(500,{'error':str(e)})
    def do_POST(self):
        try:
            origin=self.headers.get('Origin')
            if origin and origin!=f'http://{self.headers.get("Host")}': return self.send(403,{'error':'Запрос с другого сайта запрещён'})
            if not self.headers.get('Content-Type','').startswith('application/json'): return self.send(415,{'error':'Ожидается JSON'})
            size=int(self.headers.get('Content-Length','0'))
            if size<=0 or size>MAX_BODY: return self.send(413,{'error':'Слишком большой запрос (максимум 32 МБ)'})
            data=json.loads(self.rfile.read(size)); path=urlsplit(self.path).path
            if path=='/api/state':
                with LOCK: save_state(data)
                return self.send(200,{'ok':True})
            if path=='/api/upload':
                raw=base64.b64decode(data['data'],validate=True)
                if len(raw)>15*1024*1024: raise ValueError('Лимит приложения: 15 МБ на фотографию')
                ext,w,h=image_info(raw); name=uuid.uuid4().hex+'.'+ext
                (DATA/'images'/name).write_bytes(raw)
                return self.send(200,dict(file=name,url='',width=w,height=h,name=str(data.get('name','Фото'))))
            if path in ('/api/validate','/api/export'):
                state=data['state']; report=validate(state)
                missing=[pic['file'] for p in state['products'] for pic in p['pictures'] if pic.get('file') and not (DATA/'images'/pic['file']).is_file()]
                if missing: report['errors'].append('Не найдены загруженные фотографии. Добавьте файлы повторно.')
                xml=build_yml(state) if not report['errors'] else ''
                if path=='/api/validate': return self.send(200,dict(**report,xml=xml))
                if report['errors']: return self.send(400,{'error':'\n'.join(report['errors'])})
                if data.get('bundle'):
                    buf=io.BytesIO()
                    with zipfile.ZipFile(buf,'w',zipfile.ZIP_DEFLATED) as z:
                        z.writestr('catalog.yml',xml)
                        names={pic['file'] for p in state['products'] for pic in p['pictures'] if pic.get('file')}
                        for name in names: z.write(DATA/'images'/name,'images/'+name)
                        z.writestr('README.txt','Разместите содержимое images в публичной папке, указанной в настройках программы. Затем загрузите catalog.yml на Портал поставщиков. Программа не публикует фотографии автоматически.')
                    return self.send(200,buf.getvalue(),'application/zip','catalog-with-images.zip')
                return self.send(200,xml.encode('utf-8'),'application/xml; charset=utf-8','catalog.yml')
            return self.send(404,{'error':'Не найдено'})
        except (ValueError,KeyError,TypeError,OverflowError) as e: self.send(400,{'error':str(e)})
        except Exception as e: self.send(500,{'error':str(e)})

def main():
    global DATA
    parser=argparse.ArgumentParser()
    parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--data-dir',type=Path,default=DATA)
    args=parser.parse_args()
    DATA=args.data_dir.resolve()
    (DATA/'images').mkdir(parents=True,exist_ok=True)
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
    print(f'YML Студия: http://127.0.0.1:{args.port}',flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()
if __name__=='__main__': main()
