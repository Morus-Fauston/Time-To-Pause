# Tests for v0.2.3 whitelist polling architecture
# Polling = truth. A11y = instant jump only. Unknown pkgs = ignored.
import time as _time, random

N, SV, KNOWN = 3, {"com.ss.android.ugc.aweme"}, {"com.tencent.mm"}
TS = _time.mktime(_time.strptime("2026-06-25 14:00:00","%Y-%m-%d %H:%M:%S"))

class SM:
    W,L,N_="WATCHING","LEAVING","NOT_WATCHING"
    def __init__(s):
        s.s=s.N_;s.c=False;s.h=False;s.l=0.0;s.cnt=0;s.dir=False;s.sim=0.0
    def connect(s):
        s.c=True
        if s.s!=s.W:s.s=s.N_;s.h=False
    def event(s,p,t):
        s.l=t
        if not s.h:s.h=True
        if p in SV:s.s=s.W;s.cnt=0;return
        if p in KNOWN and s.s==s.W:s.s=s.L;s.cnt=0;s.dir=True
    def destroy(s):
        s.s=s.L;s.c=False;s.h=False;s.l=0.0;s.cnt=0
    def tick(s,d,t):
        s.sim=t
        if s.s==s.W:return s._procW(d)
        if s.s==s.L:return s._procL(d)
        return s._procN(d)
    def _procW(s,d):  # WATCHING
        r=d.watch()
        if r:s.cnt=0;return True
        s.cnt+=1
        if s.cnt>=N:s.s=s.N_;return False
        return True
    def _procL(s,d):  # LEAVING
        r=d.watch()
        if r:s.s=s.W;s.cnt=0;s.dir=True;return True
        s.cnt+=1
        if s.cnt>=N:s.s=s.N_;return False
        return s.dir
    def _procN(s,d):  # NOT_WATCHING
        r=d.watch()
        if r==s.dir:s.cnt+=1
        else:s.cnt=1;s.dir=r
        if s.cnt>=N:s.cnt=0;s.s=s.W if s.dir else s.N_;return s.dir
        return False

class D:
    def __init__(s,p=None):s.p=p
    def watch(s):return s.p in SV if s.p else False

def run(na,steps):
    m=SM();ok=True;print(f'\n{"="*65}\n  {na}\n{"="*65}')
    for t,act,pkg,exp in steps:
        now=TS+t;d=D(pkg)
        if act=="event":m.event(pkg,now)
        elif act=="connect":m.connect()
        elif act=="destroy":m.destroy()
        r=m.tick(d,now);p=str(pkg) if pkg else "-"
        m2="OK" if r==exp else "XX"
        if r!=exp:ok=False
        print(f'  {m2} t={t:2d} {act:7s} pkg={p:>25s} -> {m.s:12s} w={r} (exp {exp})')
    print(f'\n  {"ALL PASS" if ok else "FAILED"}')
    return ok

print("\n"+"="*65+"\n  Whitelist Polling Architecture\n"+"="*65)

# S1: Unknown system event (not in SV/KNOWN) -> IGNORED
run("S1: Unknown system event -> IGNORED",[
    (0,"event","com.ss.android.ugc.aweme",True),
    (1,"event","com.android.nfc",True),         # ignored, stays W
    (2,"tick","com.ss.android.ugc.aweme",True),
])

# S2: WeChat (KNOWN) -> L -> N=3 -> N. hits reset cnt.
run("S2: WeChat->L->poll misses->N",[
    (0,"event","com.ss.android.ugc.aweme",True),  # W, tick hit->cnt=0->T
    (5,"event","com.tencent.mm",True),             # L cnt=1, dir=T->T
    (6,"tick","com.tencent.mm",True),              # cnt=2->T
    (7,"tick","com.tencent.mm",False),             # cnt=3->N->F
])

# S3: L + poll hit -> cancel
run("S3: L+poll hit->cancel",[
    (0,"event","com.ss.android.ugc.aweme",True),
    (5,"event","com.tencent.mm",True),             # L cnt=1
    (6,"tick","com.ss.android.ugc.aweme",True),    # hit->W
])

# S4: N -> poll N=3 -> W
run("S4: N->poll N=3->W",[
    (0,"event","com.ss.android.ugc.aweme",True),
    (5,"event","com.tencent.mm",True),             # L cnt=1
    (6,"tick","com.tencent.mm",True),              # cnt=2
    (7,"tick","com.tencent.mm",False),             # cnt=3->N
    (8,"tick","com.ss.android.ugc.aweme",True),    # N cnt=1
    (9,"tick","com.ss.android.ugc.aweme",True),    # cnt=2
    (10,"tick","com.ss.android.ugc.aweme",True),   # cnt=3->W
])

# S5: Unknown app -> IGNORED
run("S5: Unknown app->IGNORED",[
    (0,"event","com.ss.android.ugc.aweme",True),
    (1,"event","com.unknown.app",True),            # ignored
    (2,"tick","com.ss.android.ugc.aweme",True),
])

# S6: W -> immediately poll miss -> N=3 -> N
run("S6: W->poll miss N=3->N",[
    (0,"event","com.ss.android.ugc.aweme",True),  # W, tick hit->cnt=0
    (1,"tick","-",True),    # miss, cnt=1->T
    (2,"tick","-",True),    # miss, cnt=2->T
    (3,"tick","-",False),   # miss, cnt=3->N->F
])

# S7: 30% loss
random.seed(42);m7=SM();m7.event("com.ss.android.ugc.aweme",TS)
print(f'\n{"="*65}\n  S7: 30% loss 60t\n{"="*65}')
fl=0;la=True
for t in range(1,61):
    h=random.random()<0.7
    r=m7.tick(D("com.ss.android.ugc.aweme" if h else None),TS+t)
    if r!=la:fl+=1;la=r
    if t%10==0:print(f'  t={t:2d} state={m7.s:12s}')
print(f'  flips={fl}')
print("Done")
