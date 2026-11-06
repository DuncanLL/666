"""Freeze current user choices into declared pack defaults, preserving shader algorithms."""
import json,re,zipfile,os
from common import ROOT,BUILD,sha,save

def prepare(source):
    options=json.loads((ROOT/'render-support/pack-defaults.json').read_text('utf8'))
    output=BUILD/'render-support/Aimoh-Eclipse-GF2.zip';temporary=output.with_suffix('.zip.writing')
    seen={key:0 for key in options};changes=[]
    with zipfile.ZipFile(source)as original,zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED)as target:
        for entry in original.infolist():
            data=original.read(entry)
            if entry.filename.endswith(('/shaders/lib/settings.glsl','/shaders/lib/res_params.glsl')):
                text=data.decode('utf8')
                for key,value in options.items():
                    if value in ('true','false'):
                        pattern=r'(?m)^(\s*)(?://\s*)?#define\s+'+re.escape(key)+r'([ \t]*(?://[^\n]*)?)$'
                        replacement=lambda m: m[1]+(''if value=='true'else'//')+'#define '+key+m[2]
                    else:
                        pattern=r'(?m)^(\s*#define\s+'+re.escape(key)+r'\s+)\S+'
                        replacement=lambda m: m[1]+value
                    text,count=re.subn(pattern,replacement,text)
                    if not count and value not in ('true','false'):
                        text,count=re.subn(r'(?m)^(\s*const\s+\w+\s+'+re.escape(key)+r'\s*=\s*)[^;]+',lambda m:m[1]+value,text)
                    seen[key]+=count
                data=text.encode('utf8')
                if data!=original.read(entry):changes.append(entry.filename)
            target.writestr(entry,data)
    assert all(n==1 for n in seen.values()),seen
    with zipfile.ZipFile(temporary)as z:assert z.testzip()is None
    os.replace(temporary,output)
    save(BUILD/'render-support/pack-defaults.json',dict(source_sha256=sha(source),output_sha256=sha(output),
         changed_entries=changes,options=options,original_shader_algorithms_changed=False))
    return output
