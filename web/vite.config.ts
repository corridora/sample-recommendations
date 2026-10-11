import {defineConfig} from 'vite';
import solid from 'vite-plugin-solid';
export default defineConfig({plugins:[solid()],server:{proxy:{'/api':'http://localhost:4107','/rum-config.js':'http://localhost:4107','/healthz':'http://localhost:4107'}},build:{outDir:'dist'}});
