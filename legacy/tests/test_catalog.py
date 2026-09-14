import copy, io, json, struct, sys, tempfile, unittest, zipfile, zlib
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import catalog, server

def valid_state():
    s=catalog.default_state();s['settings'].update(name='Тест',company='ООО «Тест & партнёры»',imageBase='https://example.ru/images')
    s['categories']=[dict(id='1',name='Мебель',parentId='')]
    values=dict(id='A001',name='Стол <Офис> & "Дом"',price='1250,50',categoryId='1',vendor='Фабрика',model='Стол 1',oksm='643',beginDate='2026-09-07T12:00',endDate='2027-09-07T12:00',delivery='true',param0='Белый',param1='Дерево',param2='100',param3='80')
    s['products']=[dict(id='test-product',templateId='basic',values=values,pictures=[dict(url='https://example.ru/table.png?a=1&b=2')])]
    return s

def png(w,h):
    def chunk(tag,data): return struct.pack('>I',len(data))+tag+data+struct.pack('>I',zlib.crc32(tag+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\x00'+b'\xc0\xd9\xc8'*w)*h))+chunk(b'IEND',b'')

class CatalogTests(unittest.TestCase):
    def test_valid_export_structure_and_roundtrip(self):
        s=valid_state();self.assertEqual(catalog.validate(s)['errors'],[])
        xml=catalog.build_yml(s,datetime(2026,9,7,13,15));root=ET.fromstring(xml);ns={'y':catalog.NS}
        self.assertEqual(root.attrib['date'],'2026-09-07 13:15')
        self.assertEqual([e.tag.split('}')[1] for e in root],['shop','categories','offers'])
        self.assertEqual(root.find('y:offers/y:offer/y:name',ns).text,s['products'][0]['values']['name'])
        self.assertEqual(root.find('y:offers/y:offer/y:price',ns).text,'1250.50')
        self.assertEqual(len(root.findall('y:offers/y:offer/y:param',ns)),4)
        self.assertEqual(root.find('y:offers/y:offer/y:picture',ns).text,s['products'][0]['pictures'][0]['url'])
        self.assertTrue(xml.startswith('<?xml version="1.0" encoding="UTF-8"?>'))
    def test_duplicate_offer_id_rejected(self):
        s=valid_state();p=copy.deepcopy(s['products'][0]);p['id']='other';s['products'].append(p)
        self.assertTrue(any('артикул повторяется' in e for e in catalog.validate(s)['errors']))
    def test_empty_catalog_cannot_export(self):
        with self.assertRaises(ValueError): catalog.build_yml(catalog.default_state())
    def test_invalid_prices(self):
        for price in ['NaN','Infinity','-1','0','abc']:
            s=valid_state();s['products'][0]['values']['price']=price
            self.assertTrue(catalog.validate(s)['errors'],price)
    def test_invalid_dates(self):
        s=valid_state();s['products'][0]['values']['endDate']='2026-02-30T10:00';self.assertTrue(catalog.validate(s)['errors'])
    def test_required_characteristics(self):
        s=valid_state();s['products'][0]['values']['param0']='';self.assertTrue(any('4 характеристики' in e for e in catalog.validate(s)['errors']))
    def test_categories_cycle(self):
        s=valid_state();s['categories'][0]['parentId']='2';s['categories'].append(dict(id='2',name='Вторая',parentId='1'))
        self.assertTrue(any('цикл' in e for e in catalog.validate(s)['errors']))
    def test_category_ids(self):
        for value in ['0','-1','a','1'*19]:
            s=valid_state();s['categories'][0]['id']=value;self.assertTrue(catalog.validate(s)['errors'])
    def test_image_url_constraints(self):
        for url in ['data:image/png;base64,a','file:///a.png','/a.png','https://localhost/a.png','http://127.0.0.1/a.png','https://a.ru/'+('a'*510)]:
            s=valid_state();s['products'][0]['pictures']=[dict(url=url)];self.assertTrue(catalog.validate(s)['errors'],url)
    def test_image_count(self):
        for count in [0,11]:
            s=valid_state();s['products'][0]['pictures']*=count;self.assertTrue(catalog.validate(s)['errors'])
    def test_local_image_public_mapping(self):
        s=valid_state();p=dict(file='a'*32+'.png',url='',width=600,height=600);s['products'][0]['pictures']=[p]
        self.assertEqual(catalog.image_url(p,s['settings']),'https://example.ru/images/'+'a'*32+'.png')
        self.assertEqual(catalog.validate(s)['errors'],[])
    def test_vat_toggle(self):
        s=valid_state();s['settings']['useVat']=True;self.assertTrue(any('НДС' in e for e in catalog.validate(s)['errors']))
        s['products'][0]['values']['vat']='NO_VAT';self.assertEqual(catalog.validate(s)['errors'],[])
        self.assertIn('<vat>NO_VAT</vat>',catalog.build_yml(s))
    def test_disallowed_xml_control(self):
        s=valid_state();s['products'][0]['values']['name']='abc\x01';self.assertTrue(catalog.validate(s)['errors'])
    def test_template_injection_and_duplicate_mapping(self):
        s=valid_state();s['templates'][0]['fields'][0]['target']='evil><x'
        with self.assertRaises(ValueError): catalog.check_shape(s)
        s=valid_state();s['templates'][0]['fields'][1]['target']='id'
        with self.assertRaises(ValueError): catalog.check_shape(s)
    def test_image_upload_formats(self):
        self.assertEqual(server.image_info(png(600,700)),('png',600,700))
        for raw in [b'GIF89a',png(249,600),png(3501,600),b'not an image']:
            with self.assertRaises(ValueError): server.image_info(raw)
        # Minimal JPEG SOF header with declared dimensions.
        jpeg=b'\xff\xd8\xff\xc0'+struct.pack('>HBHHB',8,8,600,700,0)
        self.assertEqual(server.image_info(jpeg),('jpg',700,600))
    def test_disk_persistence(self):
        with tempfile.TemporaryDirectory() as d,patch.object(server,'DATA',Path(d)):
            s=valid_state();server.save_state(s);self.assertEqual(server.read_state(),s)
            invalid=copy.deepcopy(s);invalid['products'][0]['templateId']='missing'
            with self.assertRaises(ValueError): server.save_state(invalid)
            self.assertEqual(server.read_state(),s)

if __name__=='__main__': unittest.main()
