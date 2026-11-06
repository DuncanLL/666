"""Use the existing ready-room glyph-image workflow for new Chinese controls."""
from pathlib import Path
import argparse,hashlib,json,sys
from common import ROOT,BUILD,settings
TEXT={'ChestPhysics':'胸部物理','ChestSoftness':'柔软程度','ChestDamping':'回弹阻尼',
      'ChestInertia':'惯性重量','ChestStrength':'效果幅度','ChestSuspended':'已暂停，请重置物理解算'}
def prepare(update=False):
    c=settings();sys.path.insert(0,c['python_dependencies'])
    from PIL import Image,ImageDraw,ImageFont
    root=ROOT/'game/mod/42/media/ui/AimohPMXReadyRoom';catalog=json.loads((root/'catalog.json').read_text('utf8'))
    target=BUILD/'generated-mod/42/media/ui/AimohPMXReadyRoom';target.mkdir(parents=True,exist_ok=True)
    font=ImageFont.truetype(c.get('ui_font','C:/Windows/Fonts/msyh.ttc'),28)
    bold=ImageFont.truetype(c.get('ui_font_bold','C:/Windows/Fonts/msyhbd.ttc'),32)
    for key,value in TEXT.items():
        strong=key in {'ChestSoftness','ChestDamping','ChestInertia','ChestStrength'}
        selected=bold if strong else font
        box=selected.getbbox(value);image=Image.new('RGBA',(box[2]-box[0]+4,44 if strong else 40))
        ImageDraw.Draw(image).text((2-box[0],3-box[1]),value,font=selected,fill='white')
        name='label-'+hashlib.sha256(value.encode()).hexdigest()[:14]+('-bold' if strong else '')+'.png';image.save(target/name)
        row=dict(path='media/ui/AimohPMXReadyRoom/'+name,width=image.width,height=image.height)
        if update:catalog['text'][key]=value;catalog['labels'][value]=row
        else:assert catalog['text'].get(key)==value and catalog['labels'].get(value)==row,'Run prepare_chest_labels.py --update-catalog'
    if update:(root/'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n','utf8')
    return BUILD/'generated-mod'
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--update-catalog',action='store_true');print(prepare(p.parse_args().update_catalog))
