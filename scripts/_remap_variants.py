import io

p = 'src/renderer/src/core/variants.ts'
s = io.open(p, encoding='utf-8').read()

old = """  // 留白
  V('white-1', '纯白 01', '留白', 'minimal', 'whiteborder1.webp', { theme: 'white', marginPct: 5, radiusPct: 0, footerPct: 10, logoPos: 'footer-center', borderStyle: 'none' }, '热门'),
  V('white-2', '纯白 02', '留白', 'minimal', 'whiteborder2.webp', { theme: 'white', marginPct: 6, radiusPct: 0, footerPct: 12, logoPos: 'footer-center' }),
  V('white-3', '纯白 03', '留白', 'minimal', 'whiteborder3.webp', { theme: 'white', marginPct: 4, radiusPct: 0, footerPct: 10, logoPos: 'footer-left' }),
  V('white-4', '米白 04', '留白', 'minimal', 'whiteborder4.webp', { theme: 'cream', marginPct: 6, radiusPct: 0, footerPct: 12, logoPos: 'footer-center' }),
  V('simple-1', '极简 01', '留白', 'minimal', 'simple1.webp', { theme: 'white', marginPct: 8, radiusPct: 2.5, footerPct: 18, logoPos: 'footer-center', footerAlign: 'center' }, '热门'),
  V('simple-2', '极简 02', '留白', 'minimal', 'simple2.webp', { theme: 'white', marginPct: 5, radiusPct: 0, footerPct: 12, logoPos: 'footer-center' }),
  V('simple-3', '极简 03', '留白', 'minimal', 'simple3.webp', { theme: 'white', marginPct: 5, radiusPct: 0, footerPct: 12, logoPos: 'footer-center' }),
  V('simple-4', '极简 04', '留白', 'minimal', 'simple4.webp', { theme: 'white', marginPct: 5, radiusPct: 0, footerPct: 10, logoPos: 'footer-center' }),

  // 卡片
  V('card-1', '卡片 01', '卡片', 'minimal', 'card1.webp', { theme: 'white', marginPct: 5, radiusPct: 0, footerPct: 13, logoPos: 'footer-center', borderStyle: 'thin' }),
  V('card-2', '卡片 02', '卡片', 'minimal', 'card2.webp', { theme: 'white', marginPct: 6, radiusPct: 4, footerPct: 16, logoPos: 'footer-center' }),
  V('card-3', '卡片 03', '卡片', 'brandFooter', 'card3.webp', { theme: 'white', logoPos: 'center', footerPct: 8 }),
  V('card-4', '卡片 04', '卡片', 'brandFooter', 'card4.webp', { theme: 'white', logoPos: 'center', footerPct: 8 }),

  // 人物
  V('person-1', '人物 01', '人物', 'brandFooter', 'person1.webp', { theme: 'white', logoPos: 'center', footerPct: 7 }),
  V('person-2', '人物 02', '人物', 'brandFooter', 'person2.webp', { theme: 'white', logoPos: 'center', footerPct: 8 }),
  V('person-3', '人物 03', '人物', 'minimal', 'person3.webp', { theme: 'white', marginPct: 4, footerPct: 11, logoPos: 'footer-center' }),
  V('person-4', '人物 04', '人物', 'brandFooter', 'person4.webp', { theme: 'white', logoPos: 'center', footerPct: 7 }),
  V('person-5', '人物 05', '人物', 'brandFooter', 'person5.webp', { theme: 'white', logoPos: 'left', footerPct: 7 }),
"""

new = """  // 留白
  V('white-1', '纯白 01', '留白', 'matteOverlay', 'whiteborder1.webp', { theme: 'white', marginPct: 3.0, radiusPct: 0.8, overlayMode: 'brand', overlayBrand: 'HASSELBLAD' }, '热门', { showLogo: true, showExif: false, showDate: false }, 'hasu_long_white'),
  V('white-2', '纯白 02', '留白', 'matteOverlay', 'whiteborder2.webp', { theme: 'white', marginPct: 3.0, radiusPct: 0.8, overlayMode: 'brand-exif', overlayBrand: 'FUJIFILM', exifAlign: 'center' }, '原版', { showLogo: false, showExif: true, showDate: false }),
  V('white-3', '纯白 03', '留白', 'matteOverlay', 'whiteborder3.webp', { theme: 'white', marginPct: 3.0, radiusPct: 0.8, overlayMode: 'exif', exifAlign: 'center' }, '原版', { showLogo: false, showExif: true, showDate: false }),
  V('white-4', '米白 04', '留白', 'matteOverlay', 'whiteborder4.webp', { theme: 'white', marginPct: 1.45, radiusPct: 0.5, overlayMode: 'none' }, '原版', { showLogo: false, showExif: false, showDate: false }),

  // 参数
  V('simple-1', '极简 01', '参数', 'paramStack', 'simple1.webp', { theme: 'ink', corner: 'tl' }, '热门', { showLogo: false, showExif: false, showDate: false }),
  V('simple-2', '极简 02', '参数', 'paramStack', 'simple2.webp', { theme: 'ink', corner: 'bl' }, '原版', { showLogo: false, showExif: false, showDate: false }),
  V('simple-3', '极简 03', '参数', 'paramStack', 'simple3.webp', { theme: 'ink', corner: 'tr' }, '原版', { showLogo: false, showExif: false, showDate: false }),
  V('simple-4', '极简 04', '参数', 'paramStack', 'simple4.webp', { theme: 'ink', corner: 'br' }, '原版', { showLogo: false, showExif: false, showDate: false }),

  // 卡片
  V('card-1', '卡片 01', '卡片', 'logoFooter', 'card1.webp', { theme: 'white', footerPct: 11 }, '原版', { showLogo: true, showExif: true, showDate: false }, 'laika'),
  V('card-2', '卡片 02', '卡片', 'logoFooter', 'card2.webp', { theme: 'white', footerPct: 8.5 }, '原版', { showLogo: true, showExif: false, showDate: false }, 'laika'),
  V('card-3', '卡片 03', '卡片', 'matteFooter', 'card3.webp', { theme: 'white', marginPct: 0.8, footerPct: 8.7, overlayBrand: '' }, '原版', { showLogo: false, showExif: true, showDate: false }),
  V('card-4', '卡片 04', '卡片', 'matteFooter', 'card4.webp', { theme: 'white', marginPct: 0.9, footerPct: 9.7, overlayBrand: 'Leica' }, '原版', { showLogo: true, showExif: false, showDate: false }, 'laika'),

  // 人物
  V('person-1', '人物 01', '人物', 'profileFrame', 'person1.webp', { theme: 'white', profileMode: 'header', profileName: '鹤羽轻风', marginPct: 3.6, headerPct: 9, footerPct: 4.2, overlayBrand: 'HASSELBLAD' }, '原版', { showLogo: true, showExif: false, showDate: false }, 'hasu_long_black'),
  V('person-2', '人物 02', '人物', 'profileFrame', 'person2.webp', { theme: 'white', profileMode: 'header', profileName: '鹤羽轻风', marginPct: 2.2, headerPct: 8, footerPct: 5.5, overlayBrand: 'HASSELBLAD' }, '原版', { showLogo: true, showExif: true, showDate: false }, 'hasu_long_black'),
  V('person-3', '人物 03', '人物', 'profileFrame', 'person3.webp', { theme: 'white', profileMode: 'chip', profileName: '鹤羽轻风', marginPct: 0.4, footerPct: 8.2, radiusPct: 0.4, shadow: 0, brandPlacement: 'footer', exifAlign: 'left', overlayBrand: 'HASSELBLAD' }, '原版', { showLogo: true, showExif: true, showDate: false }, 'hasu_long_black'),
  V('person-4', '人物 04', '人物', 'profileFrame', 'person4.webp', { theme: 'white', profileMode: 'chip', profileName: '鹤羽轻风', marginPct: 0.6, radiusPct: 0.4, shadow: 0, brandPlacement: 'inside', exifAlign: 'center', overlayBrand: 'HASSELBLAD' }, '原版', { showLogo: true, showExif: true, showDate: false }, 'hasu_long_white'),
  V('person-5', '人物 05', '人物', 'profileFrame', 'person5.webp', { theme: 'white', profileMode: 'footer', profileName: '鹤羽轻风', marginPct: 2.5, footerPct: 8.7, radiusPct: 0.4, shadow: 0 }, '原版', { showLogo: true, showExif: true, showDate: false }, 'sony_black'),
"""

if old not in s:
    raise SystemExit('old block not found')
s = s.replace(old, new)
s = s.replace("""  '水印边框',
  '留白',""", """  '水印边框',
  '参数',
  '留白',""")
io.open(p, 'w', encoding='utf-8').write(s)
print('done')
