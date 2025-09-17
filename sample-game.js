// --- Sample Game (no external assets) ---
// Controls: D-Pad Left/Right + A (jump). Start optional.

export const game = {
  x: 40, y: 300, vy: 0, t: 0, score: 0,
  async init(api){
    // 使うボタンのみ宣言 → 他は自動で非表示
    api.setButtons(['left','right','a','start']);
    this.api = api;
  },
  update(dt, input){
    this.t += dt;
    if(input.keys.has('ArrowLeft'))  this.x -= 120 * dt;
    if(input.keys.has('ArrowRight')) this.x += 120 * dt;
    if(input.keys.has('z') && this.y >= 300) this.vy = -260; // A: jump
    this.vy += 900 * dt;
    this.y  += this.vy * dt;
    if(this.y > 300){ this.y = 300; this.vy = 0; }
    this.score += dt * 10;
  },
  render(ctx){
    const W = ctx.canvas.width, H = ctx.canvas.height;
    ctx.fillStyle = '#000'; ctx.fillRect(0,0,W,H);

    // grid
    ctx.strokeStyle = '#1f2937';
    for(let i=0;i<W;i+=16){ ctx.beginPath(); ctx.moveTo(i,0); ctx.lineTo(i,H); ctx.stroke(); }
    for(let j=0;j<H;j+=16){ ctx.beginPath(); ctx.moveTo(0,j); ctx.lineTo(W,j); ctx.stroke(); }

    // player (green circle)
    ctx.beginPath();
    ctx.arc(Math.round(this.x), Math.round(this.y), 8, 0, Math.PI*2);
    ctx.fillStyle = '#4ade80';
    ctx.fill();

    // HUD
    ctx.fillStyle = '#9aa4af'; ctx.font = '12px ui-monospace';
    ctx.fillText('D-Pad Left/Right + A(z) to jump', 8, 16);
    ctx.fillText('Score: ' + Math.floor(this.score), 8, 32);
  }
};

export default game;
