# 대시보드 구현 계획 (2026-08-02)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 루트 URL(`/`)에서 배포 서버의 반응 분석 결과를 보여주는 정적 대시보드 1장.

**Architecture:** `static/index.html` 단일 파일(CSS·JS 인라인, 외부 의존 0). 공개 API 3종만 소비.
Spring이 자동 서빙하므로 Java 변경 없음. 스펙: `docs/plans/dashboard-0802-설계.md`.

**Tech Stack:** 순수 HTML/CSS/JS + 인라인 SVG 차트. 빌드 도구 없음.

## Global Constraints

- **코드 프리즈**: `src/main/java/**` 수정 금지. 추가 파일은 `src/main/resources/static/index.html` 하나뿐
- **외부 의존 0**: CDN·웹폰트·라이브러리 로드 금지 (폰트는 시스템 스택)
- **A안 토큰**: 배경 `#FAFAFA` · 카드 `#FFFFFF`/보더 `#E4E4E7`/radius 12px · 텍스트 `#09090B`·`#71717A` · 라인 `#18181B` · 음영 `rgba(239,68,68,.23)` · score 라벨 `#DC2626`
- **차트 코드 작성 전 `dataviz` 스킬 로드** (트리거 조건: 차트 코드 — 필수)
- 커밋: 한국어 메시지 + `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` 푸터, main 직접 커밋 (프로젝트 관행)
- EC2: `ssh -i C:\Users\aucu2\.ssh\devmatch-key.pem ec2-user@13.124.60.61`

---

### Task 1: 정적 골격 + 스타일 (데이터 없이 완성된 레이아웃)

**Files:**
- Create: `src/main/resources/static/index.html`

**Interfaces:**
- Produces: DOM id — `#contentSelect`(select) `#statusBanner`(div) `#chartHost`(div) `#tooltip`(div) `#hlBody`(tbody) `#latencyBadge`(span) `#axisRow`(div)
- Task 2~4의 JS가 이 id들에 의존한다. 변경 금지.

- [ ] **Step 1: index.html 작성** — 마크업+CSS 전체 (JS는 빈 `<script>` 자리만). 아래 코드 그대로:

```html
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SceneLog — 반응 분석 대시보드</title>
<style>
  :root{--bg:#FAFAFA;--card:#FFFFFF;--border:#E4E4E7;--t1:#09090B;--t2:#71717A;
        --line:#18181B;--band:rgba(239,68,68,.23);--score:#DC2626;
        --green-bg:#DCFCE7;--green-fg:#166534;}
  *{box-sizing:border-box;margin:0}
  body{background:var(--bg);color:var(--t1);font-size:14px;line-height:1.6;
       font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Malgun Gothic","Apple SD Gothic Neo","Noto Sans KR",sans-serif}
  .wrap{max-width:1280px;margin:0 auto;padding:32px 24px}
  header{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;margin-bottom:24px}
  .brand h1{font-size:20px;font-weight:700}
  .brand p{font-size:13px;color:var(--t2)}
  .hright{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  select,.btn{font:inherit;font-size:13px;color:var(--t1);background:var(--card);
       border:1px solid var(--border);border-radius:8px;padding:8px 14px;text-decoration:none;cursor:pointer}
  .stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px;margin-bottom:24px}
  .card{background:var(--card);border:1px solid var(--border);border-radius:12px;
        padding:20px;box-shadow:0 1px 2px rgba(0,0,0,.06)}
  .stat .lb{font-size:13px;color:var(--t2)}
  .stat .vl{font-size:26px;font-weight:700;margin:2px 0}
  .stat .nt{font-size:12px;color:var(--t2)}
  .cardTitle{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:12px}
  .cardTitle h2{font-size:15px;font-weight:700}
  .cardTitle .sub{font-size:12px;color:var(--t2)}
  #chartHost{position:relative}
  #chartHost svg{display:block;width:100%;height:auto}
  #tooltip{position:absolute;display:none;pointer-events:none;background:var(--t1);color:#fff;
       font-size:12px;border-radius:8px;padding:8px 10px;white-space:nowrap;transform:translate(-50%,-110%)}
  #axisRow{display:flex;justify-content:space-between;font-size:11px;color:var(--t2);margin-top:6px}
  table{width:100%;border-collapse:collapse;font-size:13px}
  thead th{background:#F4F4F5;color:var(--t2);font-size:12px;text-align:left;
       padding:9px 14px}
  thead th:first-child{border-radius:8px 0 0 8px}
  thead th:last-child{border-radius:0 8px 8px 0}
  tbody td{padding:12px 14px;border-bottom:1px solid #F4F4F5}
  .badge{display:inline-flex;align-items:center;gap:6px;background:var(--green-bg);color:var(--green-fg);
       font-size:12px;border-radius:999px;padding:5px 12px}
  #statusBanner{display:none;background:#FEF2F2;border:1px solid #FECACA;color:#991B1B;
       border-radius:12px;padding:14px 18px;font-size:13px;margin-bottom:24px}
  footer{font-size:11px;color:var(--t2);margin-top:24px}
  .gap{margin-bottom:24px}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="brand"><h1>SceneLog</h1><p>콘텐츠 반응 분석 대시보드</p></div>
    <div class="hright">
      <select id="contentSelect" aria-label="콘텐츠 선택"><option>불러오는 중…</option></select>
      <a class="btn" href="/swagger-ui/index.html">Swagger</a>
      <a class="btn" href="https://github.com/aucu2005/scenelog">GitHub</a>
    </div>
  </header>
  <div id="statusBanner"></div>
  <div class="stats">
    <div class="card stat"><div class="lb">인덱스 스캔 절감</div><div class="vl">1/5,044</div><div class="nt">1,205,642건 → 239건 (120만 건 실측)</div></div>
    <div class="card stat"><div class="lb">하이라이트 검출</div><div class="vl">2/2 · 100%</div><div class="nt">각본에 심은 정답 피크 전부 검출</div></div>
    <div class="card stat"><div class="lb">캐시 히트 p50</div><div class="vl">22.4ms</div><div class="nt">미스 516ms 대비 약 23배</div></div>
  </div>
  <div class="card gap">
    <div class="cardTitle"><h2>반응 타임라인</h2>
      <span class="sub">10초 버킷당 총 반응 수 · 빨간 영역 = 검출된 하이라이트</span></div>
    <div id="chartHost"><div id="tooltip"></div></div>
    <div id="axisRow"></div>
  </div>
  <div class="card">
    <div class="cardTitle"><h2>검출된 하이라이트</h2><span class="badge" id="latencyBadge">측정 중…</span></div>
    <table><thead><tr><th>구간 (재생 위치)</th><th>길이</th><th>score</th><th>method</th></tr></thead>
    <tbody id="hlBody"></tbody></table>
  </div>
  <footer>SceneLog — 개인 학습 프로젝트 · 반응 데이터는 검출 채점용 시뮬레이터가 생성한 합성 데이터입니다 · 집계 결과는 Redis 캐시로 서빙됩니다</footer>
</div>
<script>
/* Task 2~4에서 채운다 */
</script>
</body>
</html>
```

- [ ] **Step 2: 로컬 서빙 확인**

```bash
docker compose --profile app up -d --build
```
이후 `Invoke-WebRequest http://localhost:8080/ -UseBasicParsing` → **200** + HTML 본문에 `SceneLog` 포함.
브라우저 확인: 헤더·카드 3·빈 차트 카드·빈 표가 A안 시안과 같은 톤으로 보인다.

- [ ] **Step 3: 커밋** — `feat(dashboard): 루트 정적 대시보드 골격 + A안 스타일` (+푸터)

### Task 2: 데이터 로딩 — 콘텐츠 목록·집계 프로브·드롭다운

**Files:**
- Modify: `src/main/resources/static/index.html` (`<script>` 블록)

**Interfaces:**
- Consumes: Task 1의 DOM id
- Produces: `async loadContents(): Promise<{id,title,hl}[]>` (hl=highlights 배열),
  `select(content)` — 선택 콘텐츠 렌더 트리거. 전역 상태 `state = {contents, current, timeline, highlights, ms}`

- [ ] **Step 1: script 블록에 아래 코드 추가** (`/* Task 2~4에서 채운다 */` 교체):

```js
const $=id=>document.getElementById(id);
const state={contents:[],current:null,timeline:[],highlights:[],ms:0};
const fmt=s=>`${Math.floor(s/60)}:${String(s%60).padStart(2,"0")}`;
function banner(msg){const b=$("statusBanner");b.textContent=msg;b.style.display=msg?"block":"none";}
async function j(url){const r=await fetch(url);if(!r.ok)throw new Error(url+" "+r.status);return r.json();}
async function loadContents(){
  const list=await j("/api/contents");
  const items=Array.isArray(list)?list:(list.content??[]);
  const probes=await Promise.allSettled(items.map(async c=>{
    const hl=await j(`/api/contents/${c.id}/highlights`);
    return hl.length?{id:c.id,title:c.title,hl}:null;}));
  return probes.filter(p=>p.status==="fulfilled"&&p.value).map(p=>p.value);
}
async function select(content){
  state.current=content;
  const t0=performance.now();
  state.timeline=await j(`/api/contents/${content.id}/timeline`);
  state.ms=Math.max(1,Math.round(performance.now()-t0));
  state.highlights=content.hl;
  render();
}
function render(){/* Task 3~4에서 채운다 */}
(async()=>{
  try{
    state.contents=await loadContents();
    const sel=$("contentSelect");
    if(!state.contents.length){sel.innerHTML="<option>집계된 콘텐츠 없음</option>";
      banner("아직 집계된 콘텐츠가 없습니다 — Swagger에서 시뮬레이션과 집계를 실행해 보세요.");return;}
    sel.innerHTML=state.contents.map((c,i)=>`<option value="${i}">${c.title} · contentId ${c.id}</option>`).join("");
    sel.onchange=()=>select(state.contents[sel.value]);
    await select(state.contents[0]);
  }catch(e){banner("서버에 연결할 수 없습니다 — 잠시 후 새로고침해 주세요. ("+e.message+")");}
})();
```

- [ ] **Step 2: 확인** — 새로고침 → 드롭다운에 집계된 콘텐츠(로컬: 콘텐츠 1·2)가 나타난다.
  개발자도구 Network에서 highlights 프로브 후 timeline 호출 1건 확인. 콘솔 에러 0.
- [ ] **Step 3: 커밋** — `feat(dashboard): 콘텐츠 목록 로딩 + 집계 프로브 + 드롭다운`

### Task 3: SVG 차트 + 검출 음영 + 호버 툴팁

**Files:**
- Modify: `src/main/resources/static/index.html` (`render()` 채움)

**Interfaces:**
- Consumes: `state.timeline` = `[{bucketStartSec,laugh,tension,touched,bored,total}]`,
  `state.highlights` = `[{startSec,endSec,score,method}]`
- Produces: `renderChart()` — `#chartHost`에 svg 삽입, `#axisRow` 라벨 갱신

**⚠️ 이 Task 시작 전 `dataviz` 스킬을 로드할 것 (Global Constraints).**

- [ ] **Step 1: 차트 코드 추가** — `render()` 위에 삽입, `render()`에서 호출:

```js
function renderChart(){
  const T=state.timeline,H=state.highlights,W=1176,Ht=260,pad=6;
  if(!T.length){$("chartHost").innerHTML='<div id="tooltip"></div>';return;}
  const maxSec=Math.max(T[T.length-1].bucketStartSec+10,H.reduce((m,h)=>Math.max(m,h.endSec),0));
  const maxV=Math.max(...T.map(b=>b.total));
  const x=s=>s/maxSec*W, y=v=>Ht-pad-(v/maxV)*(Ht-40);
  const byBucket=new Map(T.map(b=>[b.bucketStartSec,b]));
  let pts=[];
  for(let s=0;s<maxSec;s+=10){const b=byBucket.get(s);pts.push(`${x(s).toFixed(1)},${y(b?b.total:0).toFixed(1)}`);}
  const bands=H.map(h=>`<rect x="${x(h.startSec).toFixed(1)}" y="0" width="${(x(h.endSec)-x(h.startSec)).toFixed(1)}" height="${Ht}" fill="var(--band)"/>`).join("");
  const labels=H.map(h=>`<text x="${((x(h.startSec)+x(h.endSec))/2).toFixed(1)}" y="16" text-anchor="middle" font-size="11" font-weight="700" fill="var(--score)">score ${h.score.toFixed(1)}</text>`).join("");
  $("chartHost").innerHTML=`<svg viewBox="0 0 ${W} ${Ht}" preserveAspectRatio="none">${bands}
    <polyline points="${pts.join(" ")}" fill="none" stroke="var(--line)" stroke-width="1.6" vector-effect="non-scaling-stroke"/>${labels}</svg><div id="tooltip"></div>`;
  const step=Math.ceil(maxSec/60/5/10)*10||10;
  let ax=[];for(let m=0;m<=maxSec/60;m+=step)ax.push(`<span>${m}분</span>`);
  $("axisRow").innerHTML=ax.join("");
  const host=$("chartHost");
  host.onmousemove=ev=>{
    const r=host.getBoundingClientRect();
    const sec=Math.floor((ev.clientX-r.left)/r.width*maxSec/10)*10;
    const b=byBucket.get(sec);const tip=$("tooltip");
    if(!b){tip.style.display="none";return;}
    tip.innerHTML=`<b>${fmt(sec)}</b> · 총 ${b.total}건<br>웃음 ${b.laugh} · 긴장 ${b.tension} · 감동 ${b.touched} · 지루함 ${b.bored}`;
    tip.style.left=(ev.clientX-r.left)+"px";tip.style.top=(ev.clientY-r.top)+"px";tip.style.display="block";};
  host.onmouseleave=()=>{$("tooltip").style.display="none";};
}
```
`render()`를 `function render(){renderChart();renderTable();}`로 교체 (renderTable은 Task 4 — 그 전까지 임시로 `function renderTable(){}` 스텁 추가).

- [ ] **Step 2: 확인** — 새로고침 → 꺾은선+빨간 음영+score 라벨 표시, 호버 시 타입별 툴팁,
  드롭다운 변경 시 차트 갱신. 타임라인 API 필드명은 실응답으로 대조 (`laugh/tension/touched/bored/total` —
  다르면 EC2 `/api/contents/1/timeline` 실응답 기준으로 수정).
- [ ] **Step 3: 커밋** — `feat(dashboard): SVG 타임라인 차트 + 검출 음영 + 툴팁`

### Task 4: 하이라이트 표 + 응답속도 배지

**Files:**
- Modify: `src/main/resources/static/index.html` (`renderTable()` 스텁 교체)

**Interfaces:**
- Consumes: `state.highlights`, `state.ms`, `fmt()`

- [ ] **Step 1: 표 렌더 코드** — 스텁 교체:

```js
function renderTable(){
  $("hlBody").innerHTML=state.highlights.map(h=>
    `<tr><td>${fmt(h.startSec)} ~ ${fmt(h.endSec)}</td><td>${h.endSec-h.startSec}초</td>
     <td>${h.score.toFixed(2)}</td><td><code>${h.method}</code></td></tr>`).join("")
    ||'<tr><td colspan="4" style="color:var(--t2)">검출된 구간이 없습니다</td></tr>';
  $("latencyBadge").textContent=`⚡ 타임라인 응답 ${state.ms}ms`;
}
```

- [ ] **Step 2: 로컬 종합 검증** — ① 데스크톱+375px 폭 확인 ② 콘솔 에러 0
  ③ `.\gradlew.bat test` → **34개 green** ④ `git status`에 index.html 외 변경 없음
- [ ] **Step 3: 커밋·push** — `feat(dashboard): 하이라이트 표 + 응답속도 배지 - 대시보드 완성`

### Task 5: EC2 시연 데이터 확충 (contentId 2~5)

**Files:** 없음 (API 호출만)

- [ ] **Step 1: 시뮬+집계 실행** (PowerShell, 로컬에서 EC2로):

```powershell
$base="http://13.124.60.61:8080"
$login=Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType "application/json" -Body (@{email="tester@scenelog.dev";password="password123!"}|ConvertTo-Json)
$tok=@{Authorization="Bearer $($login.accessToken)"}
foreach($id in 2..5){
  Invoke-RestMethod -Method Post -Uri "$base/api/admin/simulate?contentId=$id" -Headers $tok -TimeoutSec 180 | Out-Null
  $a=Invoke-RestMethod -Method Post -Uri "$base/api/admin/contents/$id/aggregate" -Headers $tok -TimeoutSec 120
  "content $id : events=$($a.events) buckets=$($a.buckets) highlights=$($a.highlights.Count)"}
```
Expected: 각 행에 events>0, highlights≥1. (runtime 짧은 콘텐츠는 피크 1개일 수 있음 — 정상)

### Task 6: EC2 배포 + 외부 검증 + 문서화

- [ ] **Step 1: EC2 재배포**

```powershell
ssh -i C:\Users\aucu2\.ssh\devmatch-key.pem ec2-user@13.124.60.61 "cd ~/scenelog && git pull && docker compose --profile app up -d --build 2>&1 | tail -5 && docker ps --format '{{.Names}} {{.Status}}'"
```
Expected: 4컨테이너 Up. 빌드 10~15분 (백그라운드 실행 권장).

- [ ] **Step 2: 외부 검증** — ① `http://13.124.60.61:8080/` 브라우저: 드롭다운 5편·차트·표·배지
  ② health UP ③ Swagger 200 ④ 콘솔 에러 0
- [ ] **Step 3: 문서화** — README 데모 표에 대시보드 URL 행 추가 (맨 위로), dev-log 항목,
  HANDOFF(로컬) 갱신, 스펙 문서 체크
- [ ] **Step 4: 커밋·push** — `docs: 대시보드 배포 - 루트 URL 가동`

## Self-Review 결과

- 스펙 커버리지: 헤더/카드/차트/표/배지/에러/프로브/데이터확충/검증·배포 — 전 항목 Task 1~6에 대응 ✓
- 플레이스홀더: Task 1 script 자리표시·Task 3 renderTable 스텁은 후속 Task가 채우는 명시적 인터페이스 ✓
- 타입 일관성: DOM id·state 필드·함수명 Task 간 일치 확인 ✓ (timeline 필드명은 Task 3 Step 2에서 실응답 대조로 방어)
