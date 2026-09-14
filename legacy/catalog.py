"""YML for Moscow Supplier Portal, based on supplied 2025 PDF (pp. 7–26, 37–38)."""
from datetime import datetime
from decimal import Decimal, InvalidOperation
from urllib.parse import urlsplit, quote
import re
import xml.etree.ElementTree as ET

NS = 'http://market.zakupki.mos.ru/spIntegration/Yml/1.0'
# Label, input type, required. Extra characteristics are exported as param.
FIELDS = {
 'id': ('Артикул / ID предложения', 'text', True),
 'name': ('Название товара', 'text', True),
 'price': ('Цена, ₽', 'number', True),
 'categoryId': ('Категория поставщика', 'category', True),
 'vendor': ('Производитель', 'text', True),
 'model': ('Модель', 'text', True),
 'oksm': ('Страна происхождения (код ОКСМ)', 'text', True),
 'beginDate': ('Начало предложения', 'datetime-local', True),
 'endDate': ('Окончание предложения', 'datetime-local', True),
 'delivery': ('Курьерская доставка', 'boolean', True),
 'vat': ('НДС (значение из справочника портала)', 'text', False),
 'description': ('Описание', 'textarea', False),
 'vendorCode': ('Код производителя', 'text', False),
 'ppCategory': ('ID категории портала', 'text', False),
 'ste': ('ID СТЕ', 'text', False),
 'okei': ('Код единицы измерения ОКЕИ', 'text', False),
 'min-quantity': ('Минимальное количество', 'number', False),
 'max-quantity': ('Максимальное количество', 'number', False),
 'region': ('ID региона поставки', 'text', False),
 'packageType': ('ID типа упаковки', 'text', False),
 'weight': ('Вес с упаковкой, кг', 'number', False),
 'dimensions': ('Габариты, см (Д/Ш/В)', 'text', False),
 'barcode': ('Штрихкод', 'text', False),
 'available': ('В наличии', 'boolean', False),
 'isVisibleToStateCustomers': ('Для государственных заказчиков', 'boolean', False),
 'isAvailableToIndividuals': ('Для негосударственных заказчиков', 'boolean', False),
 'manufacturer_warranty': ('Гарантия производителя', 'boolean', False),
}

def default_state():
    fields = [dict(id=k, target=k, label=v[0], type=v[1], required=v[2], unit='', default='true' if v[1]=='boolean' else '') for k,v in FIELDS.items() if k in ['id','name','price','categoryId','vendor','model','oksm','beginDate','endDate','delivery','description','vat']]
    fields += [dict(id=f'param{i}',target='param',label=n,type='text',required=True,unit='',default='') for i,n in enumerate(['Цвет','Материал','Ширина','Высота'])]
    return dict(version=1, settings=dict(name='',company='',url='',imageBase='',useVat=False), categories=[], templates=[dict(id='basic',name='Универсальная форма',description='Основные сведения и 4 характеристики товара',fields=fields)], products=[])

def http_url(value, limit=512, public=False):
    try:
        u=urlsplit(value)
        return bool(len(value)<=limit and u.scheme in ('http','https') and u.hostname and not u.username and not u.password and not re.search(r'\s',value) and (not public or u.hostname not in ('localhost','127.0.0.1','::1')))
    except ValueError:
        return False

def image_url(pic, settings):
    if pic.get('url','').strip(): return pic['url'].strip()
    if pic.get('file') and settings.get('imageBase','').strip():
        return settings['imageBase'].strip().rstrip('/')+'/'+quote(pic['file'])
    return ''

def check_shape(state):
    if not isinstance(state,dict) or state.get('version')!=1: raise ValueError('Неизвестный формат проекта')
    for key in ('templates','products','categories'):
        if not isinstance(state.get(key),list): raise ValueError('Повреждён раздел '+key)
    if not isinstance(state.get('settings'),dict): raise ValueError('Отсутствуют настройки')
    template_ids=set()
    for t in state['templates']:
        if not isinstance(t,dict) or not isinstance(t.get('id'),str) or t['id'] in template_ids or not isinstance(t.get('name'),str) or not t['name'].strip() or not isinstance(t.get('fields'),list): raise ValueError('Некорректная форма')
        template_ids.add(t['id']); ids=set(); targets=set()
        for f in t['fields']:
            if not isinstance(f,dict) or not isinstance(f.get('id'),str) or f['id'] in ids or f.get('target') not in (*FIELDS,'param') or not isinstance(f.get('label'),str) or not f['label'].strip() or f.get('type') not in ('text','number','textarea','boolean','datetime-local','category'): raise ValueError('Некорректное поле формы')
            if f['target']!='param' and f['target'] in targets: raise ValueError('Поле YML повторяется: '+f['target'])
            ids.add(f['id']); targets.add(f['target'])
            if any(not isinstance(f.get(k,''),str) for k in ('unit','default')): raise ValueError('Некорректные настройки поля')
    product_ids=set()
    for p in state['products']:
        if not isinstance(p,dict) or not isinstance(p.get('id'),str) or p['id'] in product_ids or p.get('templateId') not in template_ids or not isinstance(p.get('values'),dict) or not isinstance(p.get('pictures'),list): raise ValueError('Некорректный товар или ссылка на удалённую форму')
        product_ids.add(p['id'])
        if any(not isinstance(v,str) for v in p['values'].values()): raise ValueError('Значения полей должны быть строками')
        for pic in p['pictures']:
            if not isinstance(pic,dict) or any(not isinstance(pic.get(k,''),str) for k in ('url','file')): raise ValueError('Некорректное изображение')
            if pic.get('file') and not re.fullmatch(r'[a-f0-9]{32}\.(jpg|png)',pic['file']): raise ValueError('Некорректное имя изображения')
    if any(not isinstance(c,dict) or any(not isinstance(c.get(k,''),str) for k in ('id','name','parentId')) for c in state['categories']): raise ValueError('Некорректные категории')
    if any(not isinstance(state['settings'].get(k,''),str) for k in ('name','company','url','imageBase')): raise ValueError('Некорректные настройки магазина')

def values_for(p,t):
    return {f['target']:p['values'].get(f['id'],'').strip() for f in t['fields'] if f['target']!='param'}

def validate(state):
    check_shape(state)
    errors=[]; warnings=[]; s=state['settings']
    if not s.get('name','').strip() or len(s['name'])>20: errors.append('Магазин: укажите название длиной от 1 до 20 символов.')
    if not s.get('company','').strip(): errors.append('Магазин: укажите полное название компании.')
    if s.get('url') and not http_url(s['url'],50): errors.append('Магазин: адрес сайта должен быть HTTP/HTTPS и не длиннее 50 символов.')
    if s.get('imageBase') and (not http_url(s['imageBase'],512,True) or urlsplit(s['imageBase']).query or urlsplit(s['imageBase']).fragment): errors.append('Адрес папки фотографий: нужен публичный HTTP/HTTPS URL без параметров и фрагмента.')
    cats={}; id_re=r'[1-9][0-9]{0,17}'
    for c in state['categories']:
        if not re.fullmatch(id_re,c.get('id','')) or c['id'] in cats: errors.append('Категории: ID должен быть уникальным положительным числом, до 18 цифр.')
        if not c.get('name','').strip(): errors.append('Категории: укажите название каждой категории.')
        cats[c.get('id','')]=c
    for c in cats.values():
        seen={c['id']}; parent=c.get('parentId','')
        while parent:
            if parent not in cats or parent in seen:
                errors.append('Категории: родитель не существует или образует цикл.'); break
            seen.add(parent); parent=cats[parent].get('parentId','')
    templates={t['id']:t for t in state['templates']}; seen=set()
    if not state['products']: errors.append('Добавьте хотя бы один товар.')
    for index,p in enumerate(state['products']):
        t=templates[p['templateId']]; v=values_for(p,t); prefix=f'Товар {index+1} ({v.get("name") or "без названия"}): '
        add=lambda message: errors.append(prefix+message)
        for key, (label,_,required) in FIELDS.items():
            if (required and key!='categoryId' or key=='categoryId' and not v.get('ppCategory') or key=='vat' and s.get('useVat')) and not v.get(key): add('заполните «'+label+'».')
        for f in t['fields']:
            val=p['values'].get(f['id'],'').strip()
            if f.get('required') and not val and f['target']=='param': add('заполните «'+f['label']+'».')
            if val and f['type']=='number':
                try:
                    if not Decimal(val.replace(',','.')).is_finite(): raise InvalidOperation
                except InvalidOperation: add('«'+f['label']+'»: требуется число.')
        if not re.fullmatch(r'[a-zA-Z0-9]{1,20}',v.get('id','')): add('артикул — 1–20 латинских букв или цифр.')
        if v.get('id') in seen: add('артикул повторяется.')
        seen.add(v.get('id'))
        if not v.get('ppCategory') and v.get('categoryId') not in cats: add('выберите существующую категорию.')
        for key in ('ppCategory','ste','okei','region','packageType'):
            if v.get(key) and not re.fullmatch(r'[1-9][0-9]*',v[key]): add(key+': требуется положительный числовой ID из справочника.')
        for key in ('price','weight','min-quantity','max-quantity'):
            if v.get(key):
                try:
                    n=Decimal(v[key].replace(',','.'))
                    if not n.is_finite() or n<=0: raise InvalidOperation
                except InvalidOperation: add(key+': требуется число больше нуля.')
        try:
            if v.get('min-quantity') and v.get('max-quantity') and Decimal(v['min-quantity'].replace(',','.'))>Decimal(v['max-quantity'].replace(',','.')): add('максимум поставки меньше минимума.')
        except InvalidOperation: pass
        for key in ('beginDate','endDate'):
            try:
                if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?',v.get(key,'')): raise ValueError
                datetime.fromisoformat(v[key])
            except ValueError: add(key+': укажите корректные дату и время.')
        if v.get('endDate','')<=v.get('beginDate',''): add('окончание предложения должно быть позже начала.')
        for key,(_,typ,_) in FIELDS.items():
            if typ=='boolean' and v.get(key) and v[key] not in ('true','false'): add(key+': допустимы true / false.')
        if len(v.get('description',''))>3000: add('описание длиннее 3000 символов.')
        if v.get('dimensions') and (not re.fullmatch(r'\d+(\.\d{1,3})?/\d+(\.\d{1,3})?/\d+(\.\d{1,3})?',v['dimensions']) or any(Decimal(x)<=0 for x in v['dimensions'].split('/'))): add('габариты: три положительных числа через /, до 3 знаков после точки.')
        if v.get('barcode') and not re.fullmatch(r'(\d{8}|\d{12}|\d{13})',v['barcode']): add('штрихкод должен содержать 8, 12 или 13 цифр.')
        params=[f for f in t['fields'] if f['target']=='param' and p['values'].get(f['id'],'').strip() and f['label'].lower() not in ('модель','производитель','model','vendor')]
        if len(params)<4: add('добавьте минимум 4 характеристики (без модели и производителя), согласно стр. 16 инструкции.')
        if not 1<=len(p['pictures'])<=10: add('нужно от 1 до 10 изображений.')
        for pic in p['pictures']:
            if not http_url(image_url(pic,s),512,True): add('для каждого фото нужна публичная HTTP/HTTPS-ссылка до 512 символов; для файлов задайте адрес папки в настройках.')
            if not pic.get('file'): warnings.append(prefix+'для фото по ссылке формат, размеры и доступность автоматически не проверены.')
            elif min(pic.get('width',0),pic.get('height',0))<600: warnings.append(prefix+'рекомендуется фото не менее 600 пикселей по меньшей стороне.')
    def scan(obj):
        if isinstance(obj,str) and re.search(r'[\x00-\x08\x0b\x0c\x0e-\x1f\ud800-\udfff\ufffe\uffff]',obj): errors.append('В тексте есть недопустимые для XML символы.')
        elif isinstance(obj,dict):
            for x in obj.values(): scan(x)
        elif isinstance(obj,list):
            for x in obj: scan(x)
    scan(state)
    return dict(errors=list(dict.fromkeys(errors)),warnings=list(dict.fromkeys(warnings)))

def build_yml(state, now=None):
    report=validate(state)
    if report['errors']: raise ValueError('\n'.join(report['errors']))
    root=ET.Element('yml_catalog',{'date':(now or datetime.now()).strftime('%Y-%m-%d %H:%M'),'xmlns':NS})
    def element(parent,tag,text=None,**attrs):
        e=ET.SubElement(parent,tag,attrs)
        if text is not None: e.text=str(text)
        return e
    s=state['settings']; shop=element(root,'shop')
    element(shop,'name',s['name']); element(shop,'company',s['company'])
    if s.get('url'): element(shop,'url',s['url'])
    element(element(shop,'currencies'),'currency',id='RUB',rate='1')
    categories=element(root,'categories')
    for c in state['categories']:
        attrs={'id':c['id']}
        if c.get('parentId'): attrs['parentId']=c['parentId']
        element(categories,'category',c['name'],**attrs)
    offers=element(root,'offers'); templates={t['id']:t for t in state['templates']}
    for p in state['products']:
        t=templates[p['templateId']]; v=values_for(p,t)
        attrs={'id':v['id']}
        if v.get('available'): attrs['available']=v['available']
        offer=element(offers,'offer',**attrs)
        element(offer,'currencyId','RUB'); element(offer,'name',v['name'])
        for pic in p['pictures']: element(offer,'picture',image_url(pic,s))
        for key in ['ste','isVisibleToStateCustomers','isAvailableToIndividuals','ppCategory','categoryId','okei','min-quantity','max-quantity','beginDate','endDate','packageType','region','price','model','vendor','oksm','vendorCode','vat','delivery','manufacturer_warranty','barcode','weight','dimensions','description']:
            value=v.get(key,'')
            if not value or key=='vat' and not s.get('useVat'): continue
            if key in ('okei','packageType','region'): element(offer,key,id=value)
            else:
                if key in ('price','weight','min-quantity','max-quantity'): value=value.replace(',','.')
                if key in ('beginDate','endDate') and len(value)==16: value+=':00'
                element(offer,key,value)
        for f in t['fields']:
            val=p['values'].get(f['id'],'').strip()
            if f['target']=='param' and val:
                attrs={'name':f['label']}
                if f.get('unit'): attrs['unit']=f['unit']
                element(offer,'param',val,**attrs)
    ET.indent(root,space='  ')
    return '<?xml version="1.0" encoding="UTF-8"?>\n'+ET.tostring(root,encoding='unicode')+'\n'
