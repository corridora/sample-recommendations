import {render} from 'solid-js/web';
import {createSignal,For,onMount} from 'solid-js';
import {trace,context} from '@opentelemetry/api';
import './style.css';
type Result={items:{id:string;score:number}[];model_revision:string;review_source:string;fallback:boolean};
function App(){
  const [pop,setPop]=createSignal(1),[quality,setQuality]=createSignal(1),[result,setResult]=createSignal<Result>(),[busy,setBusy]=createSignal(false),[error,setError]=createSignal(''),[elapsed,setElapsed]=createSignal(0),[traceId,setTraceId]=createSignal(''),[product,setProduct]=createSignal('demo-product');
  async function rank(){
    if(busy())return;setBusy(true);setError('');const started=performance.now();
    const span=trace.getTracer('sample-recommendations-solid').startSpan('ui.rank-products');
    // Keep scope only for initiating fetch; never leave a span current across await.
    const pending=context.with(trace.setSpan(context.active(),span),()=>fetch(`/api/recommendations?product_id=${encodeURIComponent(product())}&popularity=${pop()}&quality=${quality()}`,{cache:'no-store'}));
    try{const response=await pending;setTraceId(response.headers.get('x-trace-id')||'');const data=await response.json();if(!response.ok)throw new Error(data.error||'Ranking unavailable');setResult(data);}
    catch(e){const message=e instanceof Error?e.message:String(e);setError(message);span.recordException(new Error(message));span.setStatus({code:2,message});}
    finally{span.end();setElapsed(Math.round(performance.now()-started));setBusy(false);}
  }
  onMount(rank);
  return <><header><a class="brand" href="/">◈ SIGNAL LAB</a><span class="badge">CORRIDORA / SAMPLE 07</span></header><main><section class="intro"><p class="eyebrow">DETERMINISTIC RECOMMENDATIONS</p><h1>Every score<br/>tells a story.</h1><p>Adjust the signals. Inspect the ranking. Follow a request across the cloud when the model changes.</p></section><section class="lab"><div class="controls"><h2>Ranking inputs</h2><label>Popularity <strong>{pop().toFixed(1)}</strong><input aria-label="Popularity" type="range" min="0.1" max="3" step="0.1" value={pop()} onInput={e=>setPop(+e.currentTarget.value)}/></label><label>Quality <strong>{quality().toFixed(1)}</strong><input aria-label="Quality" type="range" min="0.1" max="3" step="0.1" value={quality()} onInput={e=>setQuality(+e.currentTarget.value)}/></label><label>Fixture<select aria-label="Fixture" value={product()} onChange={e=>setProduct(e.currentTarget.value)}><option value="demo-product">Featured collection</option><option value="control-product">Healthy control</option><option value="demo-invalid">Seeded validation case</option></select></label><button disabled={busy()} onClick={rank}>{busy()?'Ranking…':'Run ranking →'}</button><p class="note">Fictional data · no paid AI API · no personal inputs</p></div><div class="results" aria-live="polite"><div class="result-head"><h2>Top recommendations</h2><span class="latency" data-testid="latency">{elapsed()} ms</span></div>{error()?<div role="alert" class="error">{error()}<p>Try the healthy control, or wait for the simulated rollout to expire.</p></div>:<For each={result()?.items}>{(item,i)=><article class="product"><span class="rank">0{i()+1}</span><div><h3>{item.id.replaceAll('-',' ')}</h3><p>Curated fixture / {i()===0?'best match':'alternative'}</p></div><strong>{Math.round(item.score*100)}<small>score</small></strong></article>}</For>}<div class="metadata"><span>MODEL<strong data-testid="model">{result()?.model_revision||'Loading'}</strong></span><span>REVIEW SIGNAL<strong>{result()?.review_source||'—'}</strong></span><span>TRACE<strong class="trace" data-testid="trace">{traceId()||'Awaiting request'}</strong></span></div></div></section><footer>Observe healthy → slow revision → recovery. Daily UTC window: 15:00–15:10. <a href="https://corridora.com">Explore Corridora ↗</a></footer></main></>;
}
render(()=><App/>,document.getElementById('root')!);
