import {test,expect} from '@playwright/test';
test('analytics is opt-in, sends approved IDs, separates selection and submit, and supports erasure',async({page})=>{
 const events:Record<string,unknown>[]=[];let erased=false;
 await page.route('**/api/v1/autocomplete?*',route=>route.fulfill({json:{indexVersion:'test',suggestions:[{id:'approved-1',text:'Spring Boot',type:'QUERY',score:1,highlightRanges:[]}]}}));
 await page.route('**/api/v1/autocomplete/events',async route=>{if(route.request().method()==='DELETE'){erased=true;await route.fulfill({status:204});return;}events.push(route.request().postDataJSON());await route.fulfill({json:{accepted:true}});});
 await page.goto('/');await expect(page.locator('.status')).toContainText('test');const input=page.getByRole('combobox');await input.fill('spr');await page.getByRole('option').first().click();expect(events).toHaveLength(0);
 await page.getByText('Search privacy and optional analytics',{exact:true}).click();await page.getByLabel('Search access token').fill('test-token');await page.getByLabel('Allow interaction analytics and recent submitted searches').check();
 await input.fill('spring');await page.getByRole('option').first().click();await expect.poll(()=>events.length).toBe(1);expect(events[0].kind).toBe('SELECTED');expect(events[0].termId).toBe('approved-1');expect(events[0]).not.toHaveProperty('query');
 await page.getByRole('button',{name:'Submit approved search'}).click();await expect.poll(()=>events.length).toBe(2);expect(events[1].kind).toBe('SUBMITTED');expect(events[1].intentId).toBe(events[0].intentId);
 await page.getByRole('button',{name:'Erase my search history'}).click();await expect.poll(()=>erased).toBe(true);
});
