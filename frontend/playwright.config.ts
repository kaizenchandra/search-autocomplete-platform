import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'tests',use:{baseURL:process.env.WEB_URL||'http://127.0.0.1:33127',headless:true},reporter:'list',webServer:process.env.CI?[
 {command:'java -jar ../backend/target/autocomplete-1.0.0.jar',url:'http://127.0.0.1:8080/api/v1/autocomplete/health',env:{DATABASE_URL:'jdbc:postgresql://127.0.0.1:55432/autocomplete',REDIS_PORT:'56379'},timeout:60000},
 {command:'npm run start -- --port 3000',url:'http://127.0.0.1:3000',timeout:60000}
]:undefined});
