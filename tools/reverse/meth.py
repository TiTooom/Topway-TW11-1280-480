import re,sys
# usage: meth.py app 'Lclass;' [methodname-regex]
app,cls=sys.argv[1],sys.argv[2]; pat=sys.argv[3] if len(sys.argv)>3 else '.*'
t=open(app+'.txt',encoding='utf-8',errors='replace').read()
i=t.find("Class descriptor  : '%s'"%cls)
if i<0: print("absent"); sys.exit()
j=t.find("\nClass #",i)
body=t[i:j if j>0 else None]
for m in re.finditer(r"\n([0-9a-f]+):\s+\|\[[0-9a-f]+\] (\S+)\n(.*?)(?=\n\s+catches)",body,re.S):
    name=m.group(2)
    if not re.search(pat,name.split('.')[-1]): continue
    print("==",name)
    for l in m.group(3).split('\n'):
        mm=re.match(r'^[0-9a-f]+: [0-9a-f .]+\|([0-9a-f]{4}): (.*)',l)
        if mm: print("  ",mm.group(1),re.sub(r' // (method|field|type|string)@[0-9a-f]+','',mm.group(2)).replace('Lcom/eckom/xtlibrary/twproject/','~'))
