"""Build the static handoff album from reviewed browser screenshots. Run with reportlab."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor
from reportlab.lib.utils import ImageReader
from reportlab.lib.utils import simpleSplit
import re
ROOT=Path(__file__).resolve().parent
OUT=ROOT.parent/'output/pdf/MJWatch-design.pdf'
pdfmetrics.registerFont(TTFont('Roboto',str(ROOT/'assets/Roboto-Regular.ttf')))
pdfmetrics.registerFont(TTFont('RobotoMedium',str(ROOT/'assets/Roboto-Medium.ttf')))
c=canvas.Canvas(str(OUT),pagesize=(1440,1010))
c.setTitle('MJWatch - Android & Android TV design')
c.setAuthor('MJWatch design project')
page=0
def start(title,sub=''):
 global page
 page+=1;c.setPageSize((1440,1010));c.setFillColor(HexColor('#10131a'));c.rect(0,0,1440,1010,fill=1,stroke=0)
 c.setFillColor(HexColor('#c7ff68'));c.setFont('RobotoMedium',18);c.drawString(48,960,'MJWATCH / DESIGN HANDOFF')
 c.setFillColor(HexColor('#f0f2ec'));c.setFont('RobotoMedium',32);c.drawString(48,910,title)
 c.setFillColor(HexColor('#abb5c5'));c.setFont('Roboto',15);c.drawString(48,880,sub)
 c.setFont('Roboto',12);c.drawString(48,28,'15.11.2026  /  Статичные макеты. Демонстрационные данные. Не работающий APK.')
 c.drawRightString(1392,28,f'{page:02d}')
def lines(txt,x,y,width,font=16,color='#abb5c5'):
 c.setFillColor(HexColor(color));c.setFont('Roboto',font)
 for line in simpleSplit(txt,'Roboto',font,width): c.drawString(x,y,line);y-=font*1.5
 return y
c.drawImage(str(ROOT/'screens/00-overview.jpg'),0,0,1440,1010);c.showPage();page=1
start('Две версии. Один продукт.','Решения, согласованные в задаче')
y=800
for title,body in [
 ('Нативный Android','Kotlin + Jetpack Compose Material 3 для телефона и планшета. Compose for TV для телевизора. Media3 для воспроизведения. Два APK с общей логикой каталога, раздач и истории.'),
 ('Визуальная основа','MJAnime / MJReader: Roboto, тёмная палитра и лаймовый акцент, кинообложки, стабильный TV-фокус. Телефон получает свою touch-навигацию и нижние панели.'),
 ('Путь до просмотра','Jackett / Torznab: поиск раздачи. TorrServer: metadata, файлы и HTTP-поток. Media3: подготовка, первый кадр, дорожки и сохранение позиции.'),
 ('Граница каталога','Jackett не гарантирует постеры и описания. Отдельный metadata adapter (предложен TMDB) необязателен. Без него прямой поиск и воспроизведение раздач остаются рабочими.'),
 ('Для следующего агента','Начать с design/HANDOFF.md, затем spec/DEVELOPMENT.md, INTEGRATIONS.md, FLOWS.md, DESIGN-SYSTEM.md и ACCEPTANCE.md. Версии и адреса живых серверов ещё не проверены.')]:
 c.setFillColor(HexColor('#f0f2ec'));c.setFont('RobotoMedium',23);c.drawString(48,y,title)
 y=lines(body,48,y-32,1220,20)-45
c.showPage()
src=(ROOT/'screens.js').read_text()
entries=re.findall(r"add\('([^']+)','([^']+)','([^']+)','([^']*)'",src)
for ident,title,kind,note in entries:
 start(title,'ANDROID TV' if kind.startswith('tv') else 'ANDROID / TABLET' if kind=='tablet' else 'ANDROID / MOBILE')
 path=ROOT/'screens'/f'{ident}.jpg'
 if kind.startswith('tv'):
  c.drawImage(str(path),80,145,1280,720)
  lines(note,80,112,1280,16)
 elif kind=='tablet':
  c.drawImage(str(path),120,110,1128,752)
  lines(note,120,80,1200,13)
 elif kind.startswith('landscape'):
  c.drawImage(str(path),80,270,1280,1280*412/892)
  lines(note,80,208,1200,20)
 else:
  h=760;w=h*412/892;c.drawImage(str(path),130,90,w,h)
  c.setFillColor(HexColor('#f0f2ec'));c.setFont('RobotoMedium',25);c.drawString(590,785,'Поведение экрана')
  lines(note,590,740,680,21)
  lines('Базовый кадр 412 × 892. В native-реализации: адаптивная высота, системные insets и поддержка крупного шрифта.',590,515,680,18)
  lines('Точные переходы и состояния см. design/spec/FLOWS.md. Отступы, темы и движение см. DESIGN-SYSTEM.md.',590,350,680,18)
 c.showPage()
start('Движение без лишнего шума','Состояния, читаемость и нативное поведение')
y=800
for title,body in [
 ('Фокус: 0-100 мс','Контур появляется сразу. Без изменения масштаба карточки и прыжков соседнего контента. Selected destination и current focus различаются.'),
 ('Смена изображения: 180 + 220 мс','На TV задержка намерения 180 мс, затем crossfade 220 мс. Устаревшая загрузка отменяется. Карусель не переключается сама.'),
 ('Экраны и панели: 280-360 мс','Стандартное Material движение. Shared bounds для стабильной обложки, native sheet на телефоне; все жесты имеют кнопочную альтернативу.'),
 ('Reduced motion и доступность','Системный масштаб анимаций соблюдается. Пространственное движение отключается, допустим crossfade до 100 мс. Крупный шрифт не перекрывает CTA; TalkBack получает labels и состояния.'),
 ('Честные состояния потока','Этапы добавления, metadata, файла и буфера. Никакого выдуманного процента или обещания запуска за фиксированное время. Отмена и восстановление сохраняют выбор и прогресс.')]:
 c.setFillColor(HexColor('#f0f2ec'));c.setFont('RobotoMedium',23);c.drawString(48,y,title)
 y=lines(body,48,y-34,1230,20)-42
c.showPage();c.save();print(f'{OUT}: {page} pages')
