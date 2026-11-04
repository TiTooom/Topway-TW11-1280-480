import re,struct,sys
app=sys.argv[1]; want=set(int(x,16) for x in sys.argv[2].split(','))
dex=open(app+'/classes.dex','rb').read()
lines=open(app+'.txt',encoding='utf-8',errors='replace').read().split('\n')
methods=[];cur=None
for l in lines:
    m=re.match(r'^([0-9a-f]+):\s+\|\[[0-9a-f]+\] (\S+)',l)
    if m:
        cur={'name':m.group(2),'base':int(m.group(1),16)+16,'ins':{}};methods.append(cur);continue
    if cur:
        m=re.match(r'^[0-9a-f]+: [0-9a-f .]+\|([0-9a-f]{4}): (.*)',l)
        if m: cur['ins'][int(m.group(1),16)]=m.group(2)
for me in methods:
    for idx,ins in sorted(me['ins'].items()):
        m=re.match(r'(sparse|packed)-switch v\d+, ([0-9a-f]{8})',ins)
        if not m: continue
        off=me['base']+2*int(m.group(2),16)
        ident,size=struct.unpack_from('<HH',dex,off)
        if ident not in (0x100,0x200) or size>2000: continue
        if m.group(1)=='packed':
            first=struct.unpack_from('<i',dex,off+4)[0]; tg=struct.unpack_from('<%di'%size,dex,off+8); keys=[first+i for i in range(size)]
        else:
            keys=struct.unpack_from('<%di'%size,dex,off+4); tg=struct.unpack_from('<%di'%size,dex,off+4+4*size)
        hits=[(k,t) for k,t in zip(keys,tg) if k in want]
        if len(hits)<2: continue
        print("####",me['name'])
        for k,t in hits:
            start=idx+t; out=[]
            for j in sorted(i for i in me['ins'] if i>=start)[:45]:
                s=me['ins'][j]
                if re.search(r'const-string|invoke-|sget-object|sput|iput',s): out.append(re.sub(r'v\d+(, v\d+)*|\{[^}]*\}|// (method|field|string|type)@[0-9a-f]+','',s).strip())
                if s.startswith('goto') or s.startswith('return'): break
            print("  %04x:"%k, " ; ".join(out)[:600])
