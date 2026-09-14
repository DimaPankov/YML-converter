// Optional UI smoke test: npm install --prefix /tmp/yml-ui-test playwright
// Start an isolated server: python3 server.py --port 8766 --data-dir /tmp/yml-ui-test-data
const {chromium}=require('/tmp/yml-ui-test/node_modules/playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const root='http://127.0.0.1:8766';
 try {
 const original=await (await page.request.get(root+'/api/state')).json();
 const reset=original.state;reset.products=[];reset.categories=[];reset.templates=reset.templates.filter(t=>t.id==='basic');reset.settings={name:'',company:'',url:'',imageBase:'',useVat:false};
 await page.request.post(root+'/api/state',{data:reset});
 await page.goto(root);
 await page.getByRole('heading',{name:'Ваши товары'}).waitFor();
 await page.screenshot({path:'/tmp/yml-home.png',fullPage:true});
 // Build a saved reusable template.
 await page.getByRole('button',{name:'Конструктор форм',exact:false}).click();
 await page.getByRole('button',{name:'Создать форму',exact:false}).click();
 await page.getByLabel('Название формы').fill('Тестовая мебель');
 await page.getByRole('button',{name:'Добавить поле',exact:false}).click();
 await page.getByLabel('Название поля 17',{exact:true}).fill('Глубина');
 await page.getByRole('button',{name:'Сохранить форму',exact:true}).click();
 await page.getByRole('heading',{name:'Тестовая мебель',exact:true}).waitFor();
 await page.reload();
 await page.getByRole('heading',{name:'Ваши товары'}).waitFor();
 // Shop and category.
 await page.getByRole('button',{name:'Настройки каталога',exact:false}).click();
 await page.getByLabel('Короткое название магазина').fill('Тест магазина');
 await page.getByLabel('Полное название компании').fill('ООО «Тест & компания»');
 await page.getByLabel('Публичный адрес папки фотографий').fill('https://example.ru/images');
 await page.getByRole('button',{name:'＋ Категория',exact:true}).click();
 await page.getByLabel('ID категории 1',{exact:true}).fill('1');
 await page.getByLabel('Название категории 1',{exact:true}).fill('Офисная мебель');
 await page.getByRole('button',{name:'Сохранить настройки'}).click();
 await page.getByRole('button',{name:'Товары',exact:false}).click();
 await page.getByRole('button',{name:'＋ Добавить товар',exact:true}).click();
 await page.getByLabel('Форма товара').selectOption({label:'Тестовая мебель'});
 await page.getByRole('button',{name:'Создать товар',exact:true}).click();
 for(const [label,value] of [['Артикул / ID предложения','TEST001'],['Название товара','Стол «Офис» & Дом'],['Цена, ₽','15990,50'],['Производитель','Мебельная фабрика'],['Модель','Стол 100'],['Страна происхождения','643'],['Начало предложения','2026-09-07T12:00'],['Окончание предложения','2027-09-07T12:00'],['Цвет','Белый'],['Материал','Дерево'],['Ширина','100'],['Высота','75'],['Глубина','60']]) await page.getByLabel(label,{exact:false}).fill(value);
 await page.getByLabel('Категория поставщика').selectOption('1');
 await page.locator('#upload').setInputFiles('/tmp/yml-test-photo.png');
 await page.getByText('600 × 600 px').waitFor();
 await page.screenshot({path:'/tmp/yml-editor.png',fullPage:true});
 await page.getByRole('button',{name:'Сохранить товар'}).click();
 await page.getByText('Стол «Офис» & Дом',{exact:true}).waitFor();
 await page.reload();await page.getByText('Стол «Офис» & Дом',{exact:true}).waitFor();
 await page.locator('#main').getByRole('button',{name:'↗ Экспорт YML',exact:true}).click();
 await page.getByText('Проверка данных пройдена').waitFor();
 const downloadPromise=page.waitForEvent('download');
 await page.getByRole('button',{name:'↓ Скачать .yml',exact:true}).click();
 const download=await downloadPromise;await download.saveAs('/tmp/yml-test-export.yml');
 const zipPromise=page.waitForEvent('download');await page.getByRole('button',{name:'↓ YML + фотографии (.zip)',exact:true}).click();await (await zipPromise).saveAs('/tmp/yml-test-export.zip');
 // Check application validation, save draft, then deletion of product and form.
 await page.getByRole('button',{name:'Товары',exact:false}).click();
 await page.getByRole('button',{name:'Открыть',exact:true}).click();
 await page.getByLabel('Цена, ₽',{exact:false}).fill('-1');
 await page.getByRole('button',{name:'Сохранить товар'}).click();
 await page.locator('#main').getByRole('button',{name:'↗ Экспорт YML',exact:true}).click();
 await page.getByText('price: требуется число больше нуля.',{exact:false}).waitFor();
 assert(await page.getByRole('button',{name:'↓ Скачать .yml',exact:true}).isDisabled());
 await page.getByRole('button',{name:'Товары',exact:false}).click();
 await page.getByRole('button',{name:'Удалить товар Стол',exact:false}).click();
 await page.getByRole('button',{name:'Продолжить',exact:true}).click();
 await page.getByRole('heading',{name:'Здесь начинается ваш каталог'}).waitFor();
 await page.getByRole('button',{name:'Конструктор форм',exact:false}).click();
 await page.locator('article').filter({has:page.getByRole('heading',{name:'Тестовая мебель',exact:true})}).getByRole('button',{name:'Удалить',exact:true}).click();
 await page.getByRole('button',{name:'Продолжить',exact:true}).click();
 await page.getByRole('heading',{name:'Тестовая мебель',exact:true}).waitFor({state:'detached'});
 await page.setViewportSize({width:390,height:844});
 await page.getByRole('button',{name:'Товары',exact:false}).click();
 await page.screenshot({path:'/tmp/yml-mobile.png',fullPage:true});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'No horizontal overflow');
 assert.deepEqual(errors,[]);
 console.log('UI PASS: template create/save/reload/delete, settings, product create/edit/delete, PNG upload, YML/ZIP download, validation, mobile layout; no JS errors.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
