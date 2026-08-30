"""Faithful-enough port of WireKey's cadence pipeline to measure effective WPM.

Ports: MarkovTyper.step timing, applySyntaxDeceleration, simulateAutoIndentAndTrim,
applyWpmCalibration. Error/typo generation is included so backspace chains are counted.
"""
import math, random, statistics

# ---- HumanTypingConfig ----
WPM_STD=10.0; AVG_WORD_LENGTH=5
PROB_ERROR=0.04; PROB_SWAP_ERROR=0.015; PROB_SHIFT_SYNC_ERROR=0.04; PROB_COGNITIVE_ERROR=0.08
PROB_NOTICE_ERROR=0.85; DRIFT_CORRECTION_PROB=0.8
COMPLEX_WORD_ERROR_MULT=1.5; COMMON_WORD_ERROR_MULT=0.5; COMPOSED_ACCENT_ERROR_MULT=2.0
SPEED_BOOST_COMMON_WORD=0.6; SPEED_PENALTY_COMPLEX_WORD=1.3
SPEED_BOOST_CLOSE_KEYS=0.5; SPEED_BOOST_BIGRAM=0.4
CLOSE_KEY_THRESHOLD=2.0; FAR_KEY_THRESHOLD=4.0; FAR_KEY_PENALTY=1.2
MIN_SPEED_MULTIPLIER=0.15; TIME_KEYSTROKE_STD=0.03
TIME_BACKSPACE_MEAN=0.12; TIME_BACKSPACE_STD=0.02
TIME_REACTION_MEAN=0.35; TIME_REACTION_STD=0.1
MIN_KEYSTROKE_TIME=0.02; MIN_REACTION_TIME=0.1; MIN_BACKSPACE_TIME=0.03
TIME_UPPERCASE_PENALTY=0.2; TIME_SPACE_PAUSE_MEAN=0.25; TIME_SPACE_PAUSE_STD=0.05
FATIGUE_FACTOR=1.0005; FATIGUE_CAP=1.5

def g(mean, sd):
    u1 = max(random.random(), 1e-10); u2 = random.random()
    return mean + math.sqrt(-2.0*math.log(u1))*math.cos(2*math.pi*u2)*sd

GRID = ["`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./"]
POS = {c:(r,i) for r,row in enumerate(GRID) for i,c in enumerate(row)}
def haskey(c): return c.lower() in POS
def dist(a,b):
    p1,p2 = POS.get(a.lower()), POS.get(b.lower())
    if not p1 or not p2: return FAR_KEY_THRESHOLD
    return math.hypot(p1[0]-p2[0], p1[1]-p2[1])
def neighbor(c):
    p = POS.get(c.lower())
    if not p: return random.choice("".join(GRID))
    r,i = p; n=[]
    for dr in (-1,0,1):
        for dc in (-1,0,1):
            if dr==0 and dc==0: continue
            nr,nc = r+dr, i+dc
            if 0<=nr<len(GRID) and 0<=nc<len(GRID[nr]): n.append(GRID[nr][nc])
    res = random.choice(n) if n else random.choice("".join(GRID))
    return res.upper() if c.isupper() else res

COMMON_WORDS=set("the be to of and a in that have it for not on with he as you do at this but his by from they we say her she or an will my one all would there their what so up out if about who get which go me when make can like time no just him know take people into year your good some could them see other than then now look only come its over think also back after use two how our work first well way even new want because".split())
COMMON_BIGRAMS=set("th he in er an re on at en nd ti es or te of ed is it al ar st to nt ng se ha as ou io le ve co me de hi ri ro ic ne ea ra ce".split())
PUNCT=".,!?;:'\"-()[]{}/"
def difficulty(w):
    wl = w.lower().strip(PUNCT)
    if wl in COMMON_WORDS: return "COMMON"
    if len(wl)>8 or any(ch in "zxqj" for ch in wl): return "COMPLEX"
    return "NORMAL"

COGNITIVE=[("i--","i++"),("j--","j++"),("i += 2","i++"),("left++","left--"),("right--","right++"),
("arr.length","arr.length()"),("arr.length()","arr.length"),("str.length()","str.length"),
("list.size()","list.length"),("list.size()","list.size"),("===","=="),("==","="),("<=","<"),(">=",">"),
(":","{"),("elif","else if"),("System.out.print(","System.out.println("),("Math.max(","math.max("),
("push_back","push"),("append","add")]
DSA={s.lower() for s in ["String","Integer","HashMap","LinkedList","ArrayList","TreeNode","ListNode","PriorityQueue","SweepLine","Stack","Queue","Graph","Matrix","True","False","Null","None","Return","While","Break","Continue","Catch","Finally","Private","Public","Static","Ans","Result","Count","Length","Size","Mid","Left","Right","Max","Min","System.out","Console.log","ToString","HasNext"]}

class Markov:
    def __init__(self, target, wpm):
        self.t=target
        self.session_wpm=max(10.0, g(wpm, WPM_STD))
        self.base=60.0/(self.session_wpm*AVG_WORD_LENGTH)
        self.cur=[]; self.time=0.0; self.last=None; self.fat=1.0; self.mc=0
        self.typo=None; self.ti=0; self.hist=[]

    def word_ctx(self):
        if self.mc>=len(self.t): return None
        s=self.mc
        while s>0 and self.t[s-1]!=' ': s-=1
        e=self.mc
        while e<len(self.t) and self.t[e]!=' ': e+=1
        return self.t[s:e]

    def kt(self, c):
        k=self.base*self.fat
        w=self.word_ctx()
        if w is not None:
            d=difficulty(w)
            if d=="COMMON": k*=SPEED_BOOST_COMMON_WORD
            elif d=="COMPLEX": k*=SPEED_PENALTY_COMPLEX_WORD
        if self.last is not None:
            if (self.last+c).lower() in COMMON_BIGRAMS: k*=SPEED_BOOST_BIGRAM
            else:
                dd=dist(self.last,c)
                if 0<dd<CLOSE_KEY_THRESHOLD: k*=SPEED_BOOST_CLOSE_KEYS
                elif dd>FAR_KEY_THRESHOLD: k*=FAR_KEY_PENALTY
        if c==' ': k+=g(TIME_SPACE_PAUSE_MEAN,TIME_SPACE_PAUSE_STD)
        elif c.isupper(): k+=TIME_UPPERCASE_PENALTY
        k=max(MIN_SPEED_MULTIPLIER*self.base, k)
        return max(MIN_KEYSTROKE_TIME, g(k, TIME_KEYSTROKE_STD))

    def step(self):
        cur="".join(self.cur)
        if cur==self.t: return None
        fep=len(self.t)
        for i in range(min(len(cur),len(self.t))):
            if cur[i]!=self.t[i]: fep=i; break
        finished = self.typo is not None and self.ti>=len(self.typo)
        if fep<len(cur) or finished:
            should=False
            la=self.hist[-1][1] if self.hist else None
            if la=="BACKSPACE": should=True
            elif self.mc>=len(self.t): should=True
            elif cur:
                lc=cur[-1]; d=len(cur)-fep
                if lc in " \n\t.,;!?:()[]{}\"'<>": should=True
                elif d>=2: should = random.random()<DRIFT_CORRECTION_PROB
                elif d==1: should = random.random()<PROB_NOTICE_ERROR
            if self.typo is not None:
                if self.ti<len(self.typo): should=False
                else:
                    should=True
                    if la!="BACKSPACE": self.time+=max(0.3,g(0.5,0.1))
                    self.typo=None
            if should:
                if la!="BACKSPACE": self.time+=max(MIN_REACTION_TIME,g(TIME_REACTION_MEAN,TIME_REACTION_STD))
                self.time+=max(MIN_BACKSPACE_TIME,g(TIME_BACKSPACE_MEAN,TIME_BACKSPACE_STD))
                self.cur.pop(); self.mc=len(self.cur)
                self.hist.append((self.time,"BACKSPACE","")); return True
        if self.mc>len(self.cur): self.mc=len(self.cur)
        if self.mc>=len(self.t) and self.typo is None: return None
        if self.typo is None and random.random()<PROB_COGNITIVE_ERROR:
            m=[x for x in COGNITIVE if self.t.startswith(x[0], self.mc)]
            if m:
                L=max(len(x[0]) for x in m)
                match=random.choice([x for x in m if len(x[0])==L])
                is_start = self.mc==0 or not self.t[self.mc-1].isalnum()
                if is_start or not match[0][0].isalnum():
                    self.typo=match[1]; self.ti=0
        cog = self.typo is not None and self.ti<len(self.typo)
        if cog:
            ci=self.typo[self.ti]; self.ti+=1
        else:
            ci=self.t[self.mc]
        if cog or (not haskey(ci) and ci!=' '):
            self.fat=min(FATIGUE_CAP,self.fat*FATIGUE_FACTOR)
            self.time+=max(MIN_KEYSTROKE_TIME,g(self.base*self.fat,TIME_KEYSTROKE_STD))
            self.cur.append(ci); self.last=ci
            self.hist.append((self.time,"TYPED",ci)); self.mc+=1; return True
        self.fat=min(FATIGUE_CAP,self.fat*FATIGUE_FACTOR)
        # shift sync
        if 0<self.mc<len(self.t) and self.typo is None:
            it=self.t[self.mc]; pv=self.t[self.mc-1]
            if pv.isupper() and it.islower() and it.isalpha():
                if self.mc==1 or not self.t[self.mc-2].isalpha():
                    w=self.word_ctx() or ""
                    p=PROB_SHIFT_SYNC_ERROR*(1.5 if w.lower() in DSA else 1.0)
                    if random.random()<p:
                        wc=it.upper(); self.time+=self.kt(wc)
                        self.cur.append(wc); self.last=wc
                        self.hist.append((self.time,"TYPED",wc)); self.mc+=1; return True
        prefix=self.t[:self.mc]
        lead = ci==' ' and (self.mc==0 or all(x==' ' for x in prefix.rsplit('\n',1)[-1]))
        if len(self.t)>self.mc+1 and not lead:
            ca=self.t[self.mc+1]
            if ca!=' ' and ca!=ci and random.random()<PROB_SWAP_ERROR:
                self.time+=self.kt(ca); self.cur.append(ca)
                self.time+=self.kt(ci); self.cur.append(ci); self.last=ci
                self.hist.append((self.time,"SWAP",ca+ci)); self.mc+=2; return True
        pe = 0.0 if lead else PROB_ERROR
        if not lead:
            d=difficulty(self.word_ctx() or "")
            if d=="COMPLEX": pe*=COMPLEX_WORD_ERROR_MULT
            elif d=="COMMON": pe*=COMMON_WORD_ERROR_MULT
        if random.random()<pe:
            wc=neighbor(ci); self.time+=self.kt(wc); self.cur.append(wc); self.last=wc
            self.hist.append((self.time,"TYPED",wc)); self.mc+=1
        else:
            self.time+=self.kt(ci); self.cur.append(ci); self.last=ci
            self.hist.append((self.time,"TYPED",ci)); self.mc+=1
        return True

    def run(self):
        n=0; cap=len(self.t)*10
        while self.step() is not None:
            n+=1
            if n>cap:
                self.truncated=True; break
        else:
            self.truncated=False
        return self.hist

# ---- plan (delayMs, char, isBackspace) ----
def human_plan(text, wpm):
    m=Markov(text, max(20,min(150,wpm))); ev=m.run()
    plan=[]; last=0.0
    for ts,act,ch in ev:
        d=max(0,round((ts-last)*1000)); last=ts
        if act=="BACKSPACE": plan.append([d,None,True])
        elif act=="SWAP": plan.append([d,ch[0],False]); plan.append([0,ch[1],False])
        else: plan.append([d,ch[0] if ch else None,False])
    return plan, m

HIGH_FRICTION=set("_<>:;(){}[]&|+-=!")
SHIFTY=set("*&_^%$#@!><:?{}|")
CHUNKS={"->","::","()","&&","||","==","!=",">=","<="}

def syntax_decel(plan):
    out=[]; prev=None
    for i,s in enumerate(plan):
        d,c,bs=s
        if bs or c is None:
            out.append(list(s)); prev=None; continue
        base=float(d); chunk=False
        for p in range(i+1,len(plan)):
            if plan[p][2]: break
            if plan[p][1] is not None:
                if c+plan[p][1] in CHUNKS: chunk=True
                break
        if prev is not None and prev+c in CHUNKS: chunk=True
        mult=1.0
        if c in HIGH_FRICTION: mult=g(2.1,0.3)
        elif prev in HIGH_FRICTION: mult=g(1.5,0.2)
        if chunk: mult*=1.4
        base*=mult
        if c in SHIFTY: base+=g(125.0,20.0)
        out.append([int(base),c,bs]); prev=c
    return out

def auto_indent(plan, code_mode):
    if not code_mode:
        o=[list(s) for s in plan]; lead=True
        for i,s in enumerate(o):
            if s[2]: lead=False
            elif s[1]=='\n': lead=True
            elif s[1]==' ' and lead: o[i][0]=0
            else: lead=False
        return o
    out=[]; vx=0; prev_ind=0; lastnw=None; lead=True; i=0; pending=0
    def add(s):
        nonlocal pending
        if pending>0: out.append([s[0]+pending,s[1],s[2]]); pending=0
        else: out.append(list(s))
    while i<len(plan):
        s=plan[i]
        if s[2]:
            add(s); vx=max(0,vx-1); lead=False; i+=1; continue
        c=s[1]
        if c=='\n':
            add(s); vx=prev_ind
            if lastnw in ('{','[','('): vx+=4
            first=None
            p=i+1
            while p<len(plan):
                if plan[p][2]: break
                if plan[p][1] in (' ','\t'): p+=1
                else: first=plan[p][1]; break
            d=max(400,min(1500,int(g(0.8,0.2)*1000)))
            if lastnw=='{': d+=int(g(0.3,0.1)*1000)
            if first=='}': d=max(200,min(500,int(g(0.3,0.05)*1000)))
            pending=d; lead=True; lastnw=None; i+=1
        elif lead:
            tgt=0
            while i<len(plan):
                if plan[i][2]: break
                if plan[i][1]==' ': tgt+=1; i+=1
                elif plan[i][1]=='\t': tgt+=4; i+=1
                else: break
            prev_ind=tgt
            if tgt>vx:
                for _ in range(tgt-vx): add([0,' ',False])
            vx=max(tgt,vx); lead=False
        else:
            add(s)
            if c is not None:
                vx+=1
                if not c.isspace(): lastnw=c
            i+=1
    return out

def calibrate(plan, wpm):
    wpm=max(20,min(150,wpm)); base=60000.0/(wpm*5)
    vlen=0; tot=0.0; out=[]
    for d,c,bs in plan:
        adj=float(d)
        pool=tot-vlen*base
        if (not bs) and c is not None and c.isalnum() and pool>0:
            adj-=min(base*0.15,pool)
            if adj<30.0: adj=30.0
        if bs: vlen=max(0,vlen-1)
        elif c is not None: vlen+=1
        tot+=adj; out.append([int(adj),c,bs])
    return out

def measure(text, wpm, code_mode, trials=25):
    res=[]
    for _ in range(trials):
        p,m=human_plan(text,wpm)
        p1=syntax_decel(p); p2=auto_indent(p1,code_mode); p3=calibrate(p2,wpm)
        # executed time: plan delay + HID overhead as TypingSessionManager applies it
        total=0
        for d,c,bs in p3:
            total+=d
            if bs: total+=15
            elif c is not None:
                total+=15
                if code_mode and c in '{(': total+=80+24
        eff=(len(text)/5.0)/(total/60000.0)
        res.append((eff, total, m.truncated, len(p3)))
    return res

PROSE=("The quick brown fox jumps over the lazy dog. We should think about how people "
       "work with new tools, because the first time you use one it can feel like a lot. ")
CODE = """public int maxSubArray(int[] nums) {
    int best = nums[0];
    int cur = nums[0];
    for (int i = 1; i < nums.length; i++) {
        cur = Math.max(nums[i], cur + nums[i]);
        best = Math.max(best, cur);
    }
    return best;
}
"""

def main():
    random.seed(7)
    for label, text, code_mode in (("PROSE (codeEditorMode=false)",PROSE,False),
                                   ("PROSE (codeEditorMode=true)",PROSE,True),
                                   ("JAVA CODE (codeEditorMode=true)",CODE,True)):
        for wpm in (40,60,100,150):
            r=measure(text,wpm,code_mode)
            eff=[x[0] for x in r]; trunc=sum(1 for x in r if x[2])
            print(f"{label:34s} target={wpm:3d}  effective={statistics.mean(eff):6.1f} WPM "
                  f"(min {min(eff):5.1f} max {max(eff):5.1f})  ratio={statistics.mean(eff)/wpm:.2f}"
                  + (f"  TRUNCATED {trunc}/{len(r)}" if trunc else ""))
        print()

# Guarded so cadence_delay_distribution.py can import the model without
# re-running this whole measurement as an import side effect.
if __name__ == "__main__":
    main()
