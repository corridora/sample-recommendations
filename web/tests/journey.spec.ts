import {test,expect} from '@playwright/test';
test('healthy ranking → slow model and seeded error → healthy control → recovery',async({page,request})=>{
  const token=process.env.DEMO_CONTROL_TOKEN;if(!token)throw Error('DEMO_CONTROL_TOKEN is required');
  const headers={Authorization:`Bearer ${token}`};
  const rollout=async(phase:string)=>{const r=await request.post('/api/demo/scenario',{headers,data:{phase,duration_seconds:120}});expect(r.ok()).toBeTruthy();};
  try {
    const rejectedHeartbeat=await request.post('/api/demo/heartbeat',{data:{success:true}});expect(rejectedHeartbeat.status()).toBe(401);
    await rollout('healthy');const healthyStatus=await request.get('/api/demo/status');expect(healthyStatus.ok()).toBeTruthy();const healthyRevision=(await healthyStatus.json()).model_revision;
    await page.goto('/');
    await expect(page.getByTestId('model')).toHaveText(healthyRevision);
    await expect(page.locator('.product')).toHaveCount(3);
    await page.getByLabel('Popularity').fill('3');await page.getByLabel('Quality').fill('0.1');
    await page.getByRole('button',{name:'Run ranking'}).click();
    await expect(page.locator('.product h3').first()).toHaveText('demo product');
    await rollout('regression');
    await page.getByRole('button',{name:'Run ranking'}).click();
    await expect(page.getByTestId('model')).toHaveText('fixture-bad-v2');
    await expect.poll(async()=>parseInt(await page.getByTestId('latency').innerText())).toBeGreaterThanOrEqual(1450);
    await page.getByLabel('Fixture').selectOption('demo-invalid');await page.getByRole('button',{name:'Run ranking'}).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await page.getByLabel('Fixture').selectOption('control-product');await page.getByRole('button',{name:'Run ranking'}).click();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.locator('.product')).toHaveCount(3);
    await rollout('recover');await page.getByLabel('Fixture').selectOption('demo-product');await page.getByRole('button',{name:'Run ranking'}).click();
    await expect(page.getByTestId('model')).toHaveText(healthyRevision);
    await expect(page.getByTestId('trace')).toHaveText(/[0-9a-f]{32}/);
    console.log('Recovered browser SERVER trace:',await page.getByTestId('trace').innerText());
    const heartbeat=await request.post('/api/demo/heartbeat',{headers,data:{success:true}});
    expect(heartbeat.ok(),'Daily heartbeat persistence must succeed').toBeTruthy();
    const persisted=await request.get('/api/demo/heartbeat',{headers});expect(persisted.ok()).toBeTruthy();
    const stored=await persisted.json();expect(stored.success).toBe(true);expect(stored.utc_day).toBe(new Date().toISOString().slice(0,10));
    // RUM normally batches browser records. A page lifecycle event flushes them to the local capture sink.
    await page.evaluate(()=>{document.dispatchEvent(new Event('visibilitychange'));window.dispatchEvent(new Event('pagehide'));});
    await page.waitForTimeout(1500);
  }finally{await rollout('recover');}
});
