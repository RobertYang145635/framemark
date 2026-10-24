const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

async function main() {
  const project = path.resolve(__dirname, '..')
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  const out = await page.evaluate(async () => {
    await document.fonts.ready
    await document.fonts.load('400 40px YouShe')
    await document.fonts.load('600 40px "Chakra Petch"')
    const stacks = {
      ui600: ['"Inter", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif', 600],
      yahei600: ['"Microsoft YaHei"', 600],
      yahei400: ['"Microsoft YaHei"', 400],
      youshe: ['YouShe', 400],
      noto600: ['"Noto Sans CJK SC"', 600],
      chakra600: ['"Chakra Petch"', 600],
      chakraI600: ['"Chakra Petch"', 600, 'italic'],
      sans600: ['sans-serif', 600]
    }
    const rows = Object.entries(stacks)
    const canvas = document.createElement('canvas')
    canvas.width = 1600
    canvas.height = rows.length * 110 + 20
    const ctx = canvas.getContext('2d')
    ctx.fillStyle = '#fff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.fillStyle = '#000'
    ctx.textBaseline = 'middle'
    rows.forEach(([name, [family, weight, style]], i) => {
      const y = 55 + i * 110
      ctx.font = `${style || 'normal'} ${weight} 64px ${family}`
      ctx.fillText('鹤羽轻风 HASSELBLAD 366mm', 20, y)
      ctx.font = '20px sans-serif'
      ctx.fillText(name, 20, y + 42)
    })
    return {
      data: canvas.toDataURL('image/png'),
      check: {
        yahei: document.fonts.check('600 40px "Microsoft YaHei"'),
        youshe: document.fonts.check('400 40px YouShe'),
        chakra600: document.fonts.check('600 40px "Chakra Petch"'),
        chakraItalic: document.fonts.check('italic 600 40px "Chakra Petch"')
      }
    }
  })
  fs.writeFileSync('D:/border&watermark/_research/preview-analysis/font-probe.png', Buffer.from(out.data.split(',')[1], 'base64'))
  console.log(out.check)
  await app.close()
}
main().catch((e) => { console.error(e); process.exit(1) })
