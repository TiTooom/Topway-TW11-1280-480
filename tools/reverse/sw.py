import re,struct,sys
sys.stdout.reconfigure(encoding="utf-8")
# sw.py app lineno : print, for the method containing that line, each switch key -> interesting calls/fields
app=sys.argv[1]; target=int(sys.argv[2]); depth=int(sys.argv[3]) if len(sys.argv)>3 else 60
dex=open(app+'/classes.dex','rb').read()
lines=open(app+'.txt',encoding='utf-8',errors='replace').read().split('\n')
start=target
while not re.match(r'^([0-9a-f]+):\s+\|\[',lines[start]): start-=1
hm=re.match(r'^([0-9a-f]+):\s+\|\[[0-9a-f]+\] (\S+)',lines[start]); base=int(hm.group(1),16)+16
print("METHOD",hm.group(2))
ins={}
for l in lines[start+1:]:
    if re.match(r'^\s+catches',l): break
    m=re.match(r'^[0-9a-f]+: [0-9a-f .]+\|([0-9a-f]{4}): (.*)',l)
    if m: ins[int(m.group(1),16)]=re.sub(r' // (method|field|type|string)@[0-9a-f]+','',m.group(2)).replace('Lcom/eckom/xtlibrary/twproject/','~')
order=sorted(ins)
for idx in order:
    m=re.match(r'(sparse|packed)-switch (v\d+), ([0-9a-f]{8})',ins[idx])
    if not m: continue
    off=base+2*int(m.group(3),16); ident,size=struct.unpack_from('<HH',dex,off)
    if m.group(1)=='packed':
        first=struct.unpack_from('<i',dex,off+4)[0]; tg=struct.unpack_from('<%di'%size,dex,off+8); keys=[first+i for i in range(size)]
    else:
        keys=struct.unpack_from('<%di'%size,dex,off+4); tg=struct.unpack_from('<%di'%size,dex,off+4+4*size)
    prev=[ins[j] for j in order if j<idx][-3:]
    print("## switch at %04x on %s   (prev: %s)"%(idx,m.group(2)," | ".join(prev)))
    for k,t in zip(keys,tg):
        st=idx+t; out=[]
        for j in [x for x in order if x>=st][:depth]:
            s=ins[j]
            if re.search(r'invoke-|iget [^,]+, v\d+, Landroid/os/Message|const-string|switch',s):
                s=re.sub(r'\{[^}]*\}, ','',s); out.append(s)
            if s.startswith('goto') or s.startswith('return'): break
        print("  0x%04x (%d): %s"%(k,k," ; ".join(out)))
