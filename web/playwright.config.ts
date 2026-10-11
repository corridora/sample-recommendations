import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests',workers:1,timeout:60000,use:{baseURL:process.env.BASE_URL||'http://127.0.0.1:4107',headless:true,launchOptions:{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH},trace:'retain-on-failure',screenshot:'only-on-failure'},reporter:'list'});
