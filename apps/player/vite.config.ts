import { defineConfig } from 'vite';

// GitHub Pages で <repo> 配下に出す場合は base を '/<repo>/' に
// ローカルや独自ドメイン直下なら '/' のままでOK
export default defineConfig({
  base: '/',
});
