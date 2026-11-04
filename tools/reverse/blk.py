import re,struct,sys
sys.stdout.reconfigure(encoding="utf-8")
# blk.py app line key[,key] n : full instructions of each case
app=sys.argv[1]; target=int(sys.argv[2]); want=[int(x,0) for x in sys.argv[3].split(',')]; n=int(sys.argv[4])
dex=open(app+'/classes.dex','rb').read()
lines=open(app+'.txt',encoding='utf-8',errors='replace').read().split('\n')
start=target
while not re.match(r'^([0-9a-f]+):\s+\|\[',lines[start]): start-=1
base=int(re.match(r'^([0-9a-f]+)',lines[start]).group(1),16)+16
ins={}
for l in lines[start+1:]:
    if re.match(r'^\s+catches',l): break
    m=re.match(r'^[0-9a-f]+: [0-9a-f .]+\|([0-9a-f]{4}): (.*)',l)
    if m: ins[int(m.group(1),16)]=re.sub(r' // (method|field|type|string)@[0-9a-f]+','',m.group(2)).replace('Lcom/eckom/xtlibrary/twproject/','~')
order=sorted(ins); done=False
for idx in order:
    m=re.match(r'(sparse|packed)-switch (v\d+), ([0-9a-f]{8})',ins[idx])
    if not m or done: continue
    off=base+2*int(m.group(3),16); ident,size=struct.unpack_from('<HH',dex,off)
    if m.group(1)=='packed':
        first=struct.unpack_from('<i',dex,off+4)[0]; tg=struct.unpack_from('<%di'%size,dex,off+8); keys=[first+i for i in range(size)]
    else:
        keys=struct.unpack_from('<%di'%size,dex,off+4); tg=struct.unpack_from('<%di'%size,dex,off+4+4*size)
    for k,t in zip(keys,tg):
        if k in want:
            print("=== case 0x%04x"%k)
            for j in [x for x in order if x>=idx+t][:n]: print("  %04x %s"%(j,ins[j]))
    done=True
