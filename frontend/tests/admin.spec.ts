import {test,expect} from '@playwright/test';
test('admin create edit deactivate delete import ranking rebuild and rollback controls',async({page})=>{
 let terms:Record<string,unknown>[]=[];let weight=1;const actions:string[]=[];
 await page.route('**/api/v1/admin/**',async route=>{const request=route.request();const url=new URL(request.url());const path=url.pathname;const method=request.method();actions.push(method+' '+path);let result:unknown={};
 if(path.endsWith('/suggestions')&&method==='GET')result=terms;
 else if(path.endsWith('/suggestions')&&method==='POST'){const t=request.postDataJSON();terms.push(t);result=t;}
 else if(path.endsWith('/suggestions/import')){terms.push(...request.postDataJSON());result={imported:1};}
 else if(path.includes('/suggestions/')){const id=path.split('/').pop();if(method==='DELETE')terms=terms.filter(t=>t.id!==id);else terms=terms.map(t=>t.id===id?request.postDataJSON():t);}
 else if(path.endsWith('/index/versions'))result=[{version:1,count:terms.length,createdAt:'2026-11-17',checksum:'abc'}];
 else if(path.endsWith('/index/status'))result={version:1,terms:terms.length};
 else if(path.endsWith('/ranking/policies'))result={popularityWeight:weight};
 else if(path.endsWith('/ranking/policies/default')){weight=request.postDataJSON().popularityWeight;result={popularityWeight:weight};}
 else if(path.endsWith('/analytics/queries'))result=[];
 await route.fulfill({json:result});});
 await page.goto('/admin');await page.getByLabel('OIDC access token').fill('browser-test-only');await page.getByRole('button',{name:'Connect / refresh'}).click();await expect(page.getByRole('status')).toHaveText('Completed successfully.');
 await page.getByLabel('ID',{exact:true}).fill('admin-test');await page.getByLabel('Display text').fill('Test Query');await page.getByRole('button',{name:'Create suggestion',exact:true}).click();await expect(page.getByRole('cell',{name:'Test Query',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Edit admin-test'}).click();await page.getByLabel('Display text').fill('Updated Query');await page.getByRole('button',{name:'Save changes'}).click();await expect(page.getByRole('cell',{name:'Updated Query',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Deactivate',exact:true}).click();await expect(page.getByRole('cell',{name:'false',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'Delete admin-test'}).click();await expect(page.getByRole('cell',{name:'Updated Query',exact:true})).toHaveCount(0);
 await page.getByLabel('Import JSON').fill(JSON.stringify([{id:'bulk-test',tenant:'public',text:'Imported Query',type:'QUERY',category:'tech',language:'en',active:true,popularity:1,boost:0}]));await page.getByRole('button',{name:'Import suggestions',exact:true}).click();await expect(page.getByRole('cell',{name:'Imported Query',exact:true})).toBeVisible();
 await page.getByLabel('Popularity weight').fill('0.5');await page.getByRole('button',{name:'Save ranking policy'}).click();await expect(page.getByLabel('Popularity weight')).toHaveValue('0.5');
 await page.getByRole('button',{name:'Rebuild index',exact:true}).click();await expect(page.getByRole('status')).toHaveText('Completed successfully.');await page.getByRole('button',{name:'Restore v1'}).click();await expect.poll(()=>actions.includes('POST /api/v1/admin/index/1/rollback')).toBe(true);
});
