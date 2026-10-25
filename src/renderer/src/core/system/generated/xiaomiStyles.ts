import type { StockStyle } from '../ir'

export const XIAOMI_STYLES = [
  {
    "id": "xiaomi-classic:17",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 17",
    "nameEn": "classic_17",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "classic-17-logo-xiaomi_black": {
        "file": "xiaomi/classic/17/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "classic-17-logo-xiaomi_white": {
        "file": "xiaomi/classic/17/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 128
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 128,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 50
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "classic-17-logo-xiaomi_black",
                    "themeAssets": {
                      "white": "classic-17-logo-xiaomi_black",
                      "black": "classic-17-logo-xiaomi_white",
                      "light_blur": "classic-17-logo-xiaomi_black",
                      "dark_blur": "classic-17-logo-xiaomi_white"
                    },
                    "colorIndex": 4,
                    "width": 168.25,
                    "height": 40,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 36,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 24,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.04,
                    "align": "center",
                    "outline": {
                      "paddingX": 10,
                      "height": 36,
                      "radius": 7,
                      "strokeWidth": 2,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -50
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm"
                        },
                        "font": "misans",
                        "size": 28,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=f/@{aperture}"
                        },
                        "font": "misans",
                        "size": 28,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}s"
                        },
                        "font": "misans",
                        "size": 28,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 28,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "top": 8
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0.01,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0.01,
                        "align": "left",
                        "margins": {
                          "left": 24
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-classic:18",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 18",
    "nameEn": "classic_18",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "classic-18-logo-xiaomi_black": {
        "file": "xiaomi/classic/18/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "classic-18-logo-xiaomi_white": {
        "file": "xiaomi/classic/18/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 48,
      "top": 48,
      "right": 48,
      "bottom": 124
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 124,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "classic-18-logo-xiaomi_black",
                    "themeAssets": {
                      "white": "classic-18-logo-xiaomi_black",
                      "black": "classic-18-logo-xiaomi_white",
                      "light_blur": "classic-18-logo-xiaomi_black",
                      "dark_blur": "classic-18-logo-xiaomi_white"
                    },
                    "colorIndex": 4,
                    "width": 168.25,
                    "height": 40,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 36,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 24,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.04,
                    "align": "center",
                    "outline": {
                      "paddingX": 10,
                      "height": 31,
                      "radius": 7,
                      "strokeWidth": 2,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm"
                        },
                        "font": "misans",
                        "size": 28,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=f/@{aperture}"
                        },
                        "font": "misans",
                        "size": 28,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}s"
                        },
                        "font": "misans",
                        "size": 28,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 28,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "top": 10
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0.01,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0.01,
                        "align": "left",
                        "margins": {
                          "left": 24
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-classic:19",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 19",
    "nameEn": "classic_19",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FF000000",
          "#CCFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "classic-19-logo-xiaomi_white": {
        "file": "xiaomi/classic/19/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "width": 972,
            "height": 108,
            "gravity": "centerBottom",
            "margins": {
              "bottom": 50
            },
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": true,
                "gravity": "rightVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": -28
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "color": "#FF000000",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "rightVerticalCenter",
                        "margins": {
                          "top": 9
                        },
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_yyyy_MM_dd"
                            },
                            "font": "misans",
                            "size": 20,
                            "weight": 400,
                            "color": "#80000000",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "location"
                            },
                            "font": "misans",
                            "size": 20,
                            "weight": 400,
                            "color": "#80000000",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {
                              "left": 24
                            },
                            "optional": true
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": true,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {
                      "left": 32
                    },
                    "children": [
                      {
                        "kind": "image",
                        "asset": "classic-19-logo-xiaomi_white",
                        "themeAssets": {
                          "transparent_white": "classic-19-logo-xiaomi_white"
                        },
                        "colorIndex": 2,
                        "width": 143.0125,
                        "height": 34,
                        "scale": "contain",
                        "isBrandLogo": true,
                        "margins": {
                          "right": 8
                        }
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionNumber}"
                        },
                        "font": "misans",
                        "size": 30.6,
                        "weight": 500,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionName}"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0.028,
                        "align": "center",
                        "outline": {
                          "paddingX": 8,
                          "height": 28,
                          "radius": 6.6,
                          "strokeWidth": 2,
                          "strokeColorIndex": 1
                        },
                        "margins": {},
                        "optional": true
                      },
                      {
                        "kind": "space",
                        "width": 32
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (layout) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using layout layout"
      ]
    }
  },
  {
    "id": "xiaomi-classic:20",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 20",
    "nameEn": "classic_20",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      },
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      }
    ],
    "defaultTheme": "black",
    "assets": {
      "classic-20-logo-xiaomi_white": {
        "file": "xiaomi/classic/20/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "classic-20-logo-xiaomi_black": {
        "file": "xiaomi/classic/20/xiaomi_black.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 147
    },
    "background": "#FF141414",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 147,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "classic-20-logo-xiaomi_white",
                    "themeAssets": {
                      "black": "classic-20-logo-xiaomi_white",
                      "white": "classic-20-logo-xiaomi_black"
                    },
                    "colorIndex": 4,
                    "width": 143.0125,
                    "height": 34,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 30.6,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 20,
                    "weight": 400,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 8,
                      "height": 28,
                      "radius": 6.6,
                      "strokeWidth": 2,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -50
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "height": 29,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": -0.02,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "top": 10
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "left": 20
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-classic:21",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 21",
    "nameEn": "classic_21",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#80000000",
          "#BF000000",
          "black"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#80FFFFFF",
          "#CCFFFFFF",
          "white"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#80000000",
          "#BF000000",
          "black"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#80FFFFFF",
          "#CCFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "classic-21-logo-xiaomi_black": {
        "file": "xiaomi/classic/21/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "classic-21-logo-xiaomi_white": {
        "file": "xiaomi/classic/21/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 136
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 136,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 136,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 32
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "space",
                        "width": 18
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "column",
                        "height": 30,
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "group",
                            "orientation": "row",
                            "matchWidth": false,
                            "matchHeight": false,
                            "gravity": "leftVerticalCenter",
                            "margins": {},
                            "children": [
                              {
                                "kind": "image",
                                "asset": "classic-21-logo-xiaomi_black",
                                "themeAssets": {
                                  "white": "classic-21-logo-xiaomi_black",
                                  "black": "classic-21-logo-xiaomi_white",
                                  "light_blur": "classic-21-logo-xiaomi_black",
                                  "dark_blur": "classic-21-logo-xiaomi_white"
                                },
                                "colorIndex": 3,
                                "width": 117.77499999999999,
                                "height": 28,
                                "scale": "contain",
                                "isBrandLogo": true,
                                "margins": {
                                  "right": 8
                                }
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "template",
                                  "value": "@model=@{versionNumber}"
                                },
                                "font": "misans",
                                "size": 25,
                                "weight": 500,
                                "colorIndex": 0,
                                "alpha": 1,
                                "tracking": 0,
                                "align": "left",
                                "margins": {},
                                "optional": false
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "template",
                                  "value": "@model=@{versionName}"
                                },
                                "font": "misans",
                                "size": 16,
                                "weight": 500,
                                "colorIndex": 0,
                                "alpha": 1,
                                "tracking": 0.04,
                                "align": "center",
                                "outline": {
                                  "paddingX": 6,
                                  "height": 23,
                                  "radius": 5.6,
                                  "strokeWidth": 1.8,
                                  "strokeColorIndex": 2
                                },
                                "margins": {},
                                "optional": true
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "kind": "group",
                        "orientation": "column",
                        "width": 300,
                        "height": 26,
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {
                          "top": 10
                        },
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                            },
                            "font": "misans",
                            "size": 18,
                            "weight": 500,
                            "colorIndex": 1,
                            "alpha": 1,
                            "tracking": 0.083,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -32
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 18,
                        "weight": 500,
                        "colorIndex": 1,
                        "alpha": 1,
                        "tracking": 0.083,
                        "align": "left",
                        "margins": {},
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-classic:23",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 23",
    "nameEn": "classic_23",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#80000000",
          "#BF000000",
          "black"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#80FFFFFF",
          "#CCFFFFFF",
          "white"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#80000000",
          "#BF000000",
          "black"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#80FFFFFF",
          "#CCFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "classic-23-logo-xiaomi_black": {
        "file": "xiaomi/classic/23/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "classic-23-logo-xiaomi_white": {
        "file": "xiaomi/classic/23/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 32,
      "top": 32,
      "right": 32,
      "bottom": 136
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 136,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 136,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "space",
                        "width": 18
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "column",
                        "height": 30,
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "group",
                            "orientation": "row",
                            "matchWidth": false,
                            "matchHeight": false,
                            "gravity": "leftVerticalCenter",
                            "margins": {},
                            "children": [
                              {
                                "kind": "image",
                                "asset": "classic-23-logo-xiaomi_black",
                                "themeAssets": {
                                  "white": "classic-23-logo-xiaomi_black",
                                  "black": "classic-23-logo-xiaomi_white",
                                  "light_blur": "classic-23-logo-xiaomi_black",
                                  "dark_blur": "classic-23-logo-xiaomi_white"
                                },
                                "colorIndex": 3,
                                "width": 117.77499999999999,
                                "height": 28,
                                "scale": "contain",
                                "isBrandLogo": true,
                                "margins": {
                                  "right": 8
                                }
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "template",
                                  "value": "@model=@{versionNumber}"
                                },
                                "font": "misans",
                                "size": 25,
                                "weight": 500,
                                "colorIndex": 0,
                                "alpha": 1,
                                "tracking": 0,
                                "align": "left",
                                "margins": {},
                                "optional": false
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "template",
                                  "value": "@model=@{versionName}"
                                },
                                "font": "misans",
                                "size": 16,
                                "weight": 500,
                                "colorIndex": 0,
                                "alpha": 1,
                                "tracking": 0.04,
                                "align": "center",
                                "outline": {
                                  "paddingX": 6,
                                  "height": 23,
                                  "radius": 5.6,
                                  "strokeWidth": 1.8,
                                  "strokeColorIndex": 2
                                },
                                "margins": {},
                                "optional": true
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "kind": "group",
                        "orientation": "column",
                        "width": 300,
                        "height": 26,
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {
                          "top": 10
                        },
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                            },
                            "font": "misans",
                            "size": 18,
                            "weight": 500,
                            "colorIndex": 1,
                            "alpha": 1,
                            "tracking": 0.083,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 18,
                        "weight": 500,
                        "colorIndex": 1,
                        "alpha": 1,
                        "tracking": 0.083,
                        "align": "left",
                        "margins": {},
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-classic:71",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 71",
    "nameEn": "classic_71",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FFFFFFFF",
          "#FFFFFFFF",
          "#FFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "classic-71-logo-xiaomi_black": {
        "file": "xiaomi/classic/71/xiaomi_black.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "width": 972,
            "height": 108,
            "gravity": "centerBottom",
            "margins": {
              "bottom": 50
            },
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": true,
                "gravity": "rightVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": -28
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "rightVerticalCenter",
                        "margins": {
                          "top": 9
                        },
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_yyyy_MM_dd"
                            },
                            "font": "misans",
                            "size": 20,
                            "weight": 400,
                            "color": "#80FFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "location"
                            },
                            "font": "misans",
                            "size": 20,
                            "weight": 400,
                            "color": "#80FFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {
                              "left": 24
                            },
                            "optional": true
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": true,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {
                      "left": 32
                    },
                    "children": [
                      {
                        "kind": "image",
                        "asset": "classic-71-logo-xiaomi_black",
                        "themeAssets": {
                          "transparent_white": "classic-71-logo-xiaomi_black"
                        },
                        "colorIndex": 2,
                        "width": 143.0125,
                        "height": 34,
                        "scale": "contain",
                        "isBrandLogo": true,
                        "margins": {
                          "right": 8
                        }
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionNumber}"
                        },
                        "font": "misans",
                        "size": 30.6,
                        "weight": 500,
                        "color": "#FFFFFF",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionName}"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFFFFF",
                        "alpha": 1,
                        "tracking": 0.028,
                        "align": "center",
                        "outline": {
                          "paddingX": 8,
                          "height": 28,
                          "radius": 6.6,
                          "strokeWidth": 2,
                          "strokeColorIndex": 1
                        },
                        "margins": {},
                        "optional": true
                      },
                      {
                        "kind": "space",
                        "width": 32
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (layout) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using layout layout"
      ]
    }
  },
  {
    "id": "xiaomi-classic:72",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 72",
    "nameEn": "classic_72",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      },
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      }
    ],
    "defaultTheme": "black",
    "assets": {
      "classic-72-logo-xiaomi_white": {
        "file": "xiaomi/classic/72/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "classic-72-logo-xiaomi_black": {
        "file": "xiaomi/classic/72/xiaomi_black.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 147
    },
    "background": "#FF141414",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 147,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "classic-72-logo-xiaomi_white",
                    "themeAssets": {
                      "black": "classic-72-logo-xiaomi_white",
                      "white": "classic-72-logo-xiaomi_black"
                    },
                    "colorIndex": 4,
                    "width": 143.0125,
                    "height": 34,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 30.6,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 20,
                    "weight": 400,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 8,
                      "height": 28,
                      "radius": 6.6,
                      "strokeWidth": 2,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -50
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "height": 29,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": -0.02,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "top": 10
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "left": 20
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-classic:95",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 95",
    "nameEn": "classic_95",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "classic-95-logo-xiaomi_black": {
        "file": "xiaomi/classic/95/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "classic-95-logo-xiaomi_white": {
        "file": "xiaomi/classic/95/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 122
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 122,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "classic-95-logo-xiaomi_black",
                    "themeAssets": {
                      "white": "classic-95-logo-xiaomi_black",
                      "black": "classic-95-logo-xiaomi_white",
                      "light_blur": "classic-95-logo-xiaomi_black",
                      "dark_blur": "classic-95-logo-xiaomi_white"
                    },
                    "colorIndex": 4,
                    "width": 117.77499999999999,
                    "height": 28,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 25.2,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 15,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.032,
                    "align": "center",
                    "outline": {
                      "paddingX": 6,
                      "height": 22,
                      "radius": 5.6,
                      "strokeWidth": 1.8,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -48
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=f/@{aperture}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}s"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "top": 8
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd"
                        },
                        "font": "misans",
                        "size": 18,
                        "weight": 500,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0.01,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 18,
                        "weight": 500,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0.01,
                        "align": "left",
                        "margins": {
                          "left": 18
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-classic:96",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 96",
    "nameEn": "classic_96",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "classic-96-logo-xiaomi_black": {
        "file": "xiaomi/classic/96/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "classic-96-logo-xiaomi_white": {
        "file": "xiaomi/classic/96/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 48,
      "top": 48,
      "right": 48,
      "bottom": 122
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 122,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "classic-96-logo-xiaomi_black",
                    "themeAssets": {
                      "white": "classic-96-logo-xiaomi_black",
                      "black": "classic-96-logo-xiaomi_white",
                      "light_blur": "classic-96-logo-xiaomi_black",
                      "dark_blur": "classic-96-logo-xiaomi_white"
                    },
                    "colorIndex": 4,
                    "width": 117.77499999999999,
                    "height": 28,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 25.2,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 15,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.032,
                    "align": "center",
                    "outline": {
                      "paddingX": 6,
                      "height": 22,
                      "radius": 5.6,
                      "strokeWidth": 1.8,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=f/@{aperture}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}s"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.02,
                        "align": "left",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "top": 8
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd"
                        },
                        "font": "misans",
                        "size": 18,
                        "weight": 500,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0.01,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 18,
                        "weight": 500,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0.01,
                        "align": "left",
                        "margins": {
                          "left": 18
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-classic:97",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 97",
    "nameEn": "classic_97",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FFFFFFFF",
          "#FFFFFFFF",
          "#FFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "classic-97-logo-xiaomi_white": {
        "file": "xiaomi/classic/97/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "width": 993.6,
            "height": 108,
            "gravity": "centerBottom",
            "margins": {
              "bottom": 40
            },
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": true,
                "gravity": "rightVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": -28
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 500,
                        "color": "#FF000000",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "rightVerticalCenter",
                        "margins": {
                          "top": 8
                        },
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_yyyy_MM_dd"
                            },
                            "font": "misans",
                            "size": 18,
                            "weight": 400,
                            "color": "#80000000",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "location"
                            },
                            "font": "misans",
                            "size": 18,
                            "weight": 400,
                            "color": "#80000000",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {
                              "left": 24
                            },
                            "optional": true
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": true,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {
                      "left": 32
                    },
                    "children": [
                      {
                        "kind": "image",
                        "asset": "classic-97-logo-xiaomi_white",
                        "themeAssets": {
                          "transparent_white": "classic-97-logo-xiaomi_white"
                        },
                        "colorIndex": 2,
                        "width": 100.94999999999999,
                        "height": 24,
                        "scale": "contain",
                        "isBrandLogo": true,
                        "margins": {
                          "right": 8
                        }
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionNumber}"
                        },
                        "font": "misans",
                        "size": 21.6,
                        "weight": 500,
                        "color": "#FFFFFF",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionName}"
                        },
                        "font": "misans",
                        "size": 13.2,
                        "weight": 400,
                        "color": "#FFFFFF",
                        "alpha": 1,
                        "tracking": 0.028,
                        "align": "center",
                        "outline": {
                          "paddingX": 5,
                          "height": 18.5,
                          "radius": 4.4,
                          "strokeWidth": 1.32,
                          "strokeColorIndex": 1
                        },
                        "margins": {},
                        "optional": true
                      },
                      {
                        "kind": "space",
                        "width": 32
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (layout) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using layout layout"
      ]
    }
  },
  {
    "id": "xiaomi-classic:98",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "经典 · 98",
    "nameEn": "classic_98",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white"
        ]
      },
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "#80000000",
          "black"
        ]
      }
    ],
    "defaultTheme": "black",
    "assets": {
      "classic-98-logo-xiaomi_white": {
        "file": "xiaomi/classic/98/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "classic-98-logo-xiaomi_black": {
        "file": "xiaomi/classic/98/xiaomi_black.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 148
    },
    "background": "#FF141414",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 148,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "classic-98-logo-xiaomi_white",
                    "themeAssets": {
                      "black": "classic-98-logo-xiaomi_white",
                      "white": "classic-98-logo-xiaomi_black"
                    },
                    "colorIndex": 4,
                    "width": 117.77499999999999,
                    "height": 28,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 25.2,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 15.4,
                    "weight": 400,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 6,
                      "height": 22,
                      "radius": 5,
                      "strokeWidth": 1.54,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -48
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "height": 27,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": -0.02,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "top": 12
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd"
                        },
                        "font": "misans",
                        "size": 18,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 18,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "left": 20
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-disney:31",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "迪士尼 · 31",
    "nameEn": "disney_31",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "disney-31-snow_white": {
        "file": "xiaomi/disney/31/snow_white.webp",
        "width": 160,
        "height": 160
      },
      "disney-31-logo-xiaomi_white": {
        "file": "xiaomi/disney/31/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "disney-31-disney_icon": {
        "file": "xiaomi/disney/31/disney_icon.webp",
        "width": 134,
        "height": 58
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "centerBottom",
            "margins": {
              "bottom": 32
            },
            "children": [
              {
                "kind": "image",
                "asset": "disney-31-snow_white",
                "width": 80,
                "height": 80,
                "scale": "contain",
                "isBrandLogo": false,
                "margins": {}
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "topHorCenter",
                "margins": {
                  "top": 12
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "topHorCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "topHorCenter",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "disney-31-logo-xiaomi_white",
                            "themeAssets": {
                              "transparent_white": "disney-31-logo-xiaomi_white"
                            },
                            "colorIndex": 3,
                            "width": 92.5375,
                            "height": 22,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 19,
                            "weight": 500,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 12,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.032,
                            "align": "center",
                            "outline": {
                              "paddingX": 5,
                              "height": 17,
                              "radius": 4,
                              "strokeWidth": 1,
                              "strokeColorIndex": 2
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "image",
                        "asset": "disney-31-disney_icon",
                        "width": 64.6896551724138,
                        "height": 28,
                        "scale": "contain",
                        "isBrandLogo": false,
                        "margins": {
                          "left": 13
                        }
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "SPECIAL  EDITION"
                    },
                    "font": "misans",
                    "size": 14,
                    "weight": 500,
                    "color": "#FFF5F5F5",
                    "alpha": 1,
                    "tracking": 0.785,
                    "align": "left",
                    "margins": {
                      "top": 8
                    },
                    "optional": false
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom layout",
        "3 anchor presets in config; default bottom used"
      ]
    }
  },
  {
    "id": "xiaomi-disney:32",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "迪士尼 · 32",
    "nameEn": "disney_32",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "disney-32-queen_dark": {
        "file": "xiaomi/disney/32/queen_dark.webp",
        "width": 160,
        "height": 161
      },
      "disney-32-logo-xiaomi_white": {
        "file": "xiaomi/disney/32/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "disney-32-disney_icon": {
        "file": "xiaomi/disney/32/disney_icon.webp",
        "width": 134,
        "height": 58
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "centerBottom",
            "margins": {
              "bottom": 32
            },
            "children": [
              {
                "kind": "image",
                "asset": "disney-32-queen_dark",
                "width": 80,
                "height": 80,
                "scale": "contain",
                "isBrandLogo": false,
                "margins": {}
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "topHorCenter",
                "margins": {
                  "top": 12
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "topHorCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "topHorCenter",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "disney-32-logo-xiaomi_white",
                            "themeAssets": {
                              "transparent_white": "disney-32-logo-xiaomi_white"
                            },
                            "colorIndex": 3,
                            "width": 92.5375,
                            "height": 22,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 19,
                            "weight": 500,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 12,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.032,
                            "align": "center",
                            "outline": {
                              "paddingX": 5,
                              "height": 17,
                              "radius": 4,
                              "strokeWidth": 1,
                              "strokeColorIndex": 2
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "image",
                        "asset": "disney-32-disney_icon",
                        "width": 64.6896551724138,
                        "height": 28,
                        "scale": "contain",
                        "isBrandLogo": false,
                        "margins": {
                          "left": 13
                        }
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "SPECIAL  EDITION"
                    },
                    "font": "misans",
                    "size": 14,
                    "weight": 500,
                    "color": "#FFF5F5F5",
                    "alpha": 1,
                    "tracking": 0.785,
                    "align": "left",
                    "margins": {
                      "top": 8
                    },
                    "optional": false
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom layout",
        "3 anchor presets in config; default bottom used"
      ]
    }
  },
  {
    "id": "xiaomi-disney:33",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "迪士尼 · 33",
    "nameEn": "disney_33",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#CCFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "disney-33-logo-xiaomi_white": {
        "file": "xiaomi/disney/33/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "disney-33-disney_icon": {
        "file": "xiaomi/disney/33/disney_icon.webp",
        "width": 456,
        "height": 126
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "width": 1080,
            "gravity": "leftBottom",
            "margins": {
              "bottom": 32
            },
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "topHorCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "topHorCenter",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "disney-33-logo-xiaomi_white",
                            "themeAssets": {
                              "transparent_white": "disney-33-logo-xiaomi_white"
                            },
                            "colorIndex": 3,
                            "width": 143.0125,
                            "height": 34,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 30.6,
                            "weight": 500,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 20,
                            "weight": 400,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0.028,
                            "align": "center",
                            "outline": {
                              "paddingX": 8,
                              "height": 28,
                              "radius": 6.6,
                              "strokeWidth": 2,
                              "strokeColorIndex": 2
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "image",
                        "asset": "disney-33-disney_icon",
                        "width": 152,
                        "height": 42,
                        "scale": "contain",
                        "isBrandLogo": false,
                        "margins": {
                          "left": 20
                        }
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -48
                },
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                    },
                    "font": "misans",
                    "size": 27,
                    "weight": 400,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.037,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom layout",
        "3 anchor presets in config; default bottom used"
      ]
    }
  },
  {
    "id": "xiaomi-film:12",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "胶片 · 12",
    "nameEn": "film_12",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "top_bg",
        "label": "top_bg",
        "swatch": "#FFFFFF",
        "background": "#FFFFFF",
        "foreground": [
          "#FF121212",
          "#4D000000",
          "#BFF4CA32",
          "F4CA32"
        ]
      },
      {
        "id": "bottom_bg",
        "label": "bottom_bg",
        "swatch": "#FFFFFF",
        "background": "#FFFFFF",
        "foreground": [
          "#FF121212",
          "#4D000000",
          "#BFF4CA32",
          "F4CA32"
        ]
      },
      {
        "id": "transparent",
        "label": "transparent",
        "swatch": "#FF121212",
        "background": "#FF121212",
        "foreground": [
          "#FF121212",
          "#4D000000",
          "#BFF4CA32",
          "F4CA32"
        ]
      }
    ],
    "defaultTheme": "transparent",
    "assets": {
      "film-12-logo-xiaomi_F4CA32": {
        "file": "xiaomi/film/12/xiaomi_F4CA32.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 186,
      "right": 0,
      "bottom": 282
    },
    "background": "#FF121212",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 282,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 150,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                },
                "font": "misans",
                "size": 18,
                "weight": 500,
                "color": "#80F4CA32",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {
                  "left": 40
                },
                "optional": false
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "film-12-logo-xiaomi_F4CA32",
                    "themeAssets": {
                      "top_bg": "film-12-logo-xiaomi_F4CA32",
                      "bottom_bg": "film-12-logo-xiaomi_F4CA32",
                      "transparent": "film-12-logo-xiaomi_F4CA32"
                    },
                    "colorIndex": 3,
                    "width": 109.3625,
                    "height": 26,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 24,
                    "weight": 500,
                    "color": "#FFF4CA32",
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 15,
                    "weight": 400,
                    "color": "#FFF4CA32",
                    "alpha": 1,
                    "tracking": 0.032,
                    "align": "center",
                    "outline": {
                      "paddingX": 6,
                      "height": 22,
                      "radius": 5.6,
                      "strokeWidth": 1.8,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                },
                "font": "misans",
                "size": 18,
                "weight": 500,
                "color": "#80F4CA32",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {
                  "left": -40
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-film:13",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "胶片 · 13",
    "nameEn": "film_13",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "texture_black",
        "label": "texture_black",
        "swatch": "#FF1C1D1F;@img=background_texture_1.png",
        "background": "#FF1C1D1F;@img=background_texture_1.png",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "texture_white",
        "label": "texture_white",
        "swatch": "#FFFFFFFF;@img=background_texture_1.png",
        "background": "#FFFFFFFF;@img=background_texture_1.png",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BF000000",
          "black"
        ]
      },
      {
        "id": "texture_rose_gray",
        "label": "texture_rose_gray",
        "swatch": "#FF9F7F85;@img=background_texture_1.png",
        "background": "#FF9F7F85;@img=background_texture_1.png",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "texture_blue_gray_light",
        "label": "texture_blue_gray_light",
        "swatch": "#FFB3BEC7;@img=background_texture_1.png",
        "background": "#FFB3BEC7;@img=background_texture_1.png",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "texture_blue_gray_dark",
        "label": "texture_blue_gray_dark",
        "swatch": "#FF63788B;@img=background_texture_1.png",
        "background": "#FF63788B;@img=background_texture_1.png",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "texture_blue_gray",
        "label": "texture_blue_gray",
        "swatch": "#FF737F7D;@img=background_texture_1.png",
        "background": "#FF737F7D;@img=background_texture_1.png",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "texture_green_dark",
        "label": "texture_green_dark",
        "swatch": "#FF39372C;@img=background_texture_1.png",
        "background": "#FF39372C;@img=background_texture_1.png",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "texture_green_gray_light",
        "label": "texture_green_gray_light",
        "swatch": "#FF7D8473;@img=background_texture_1.png",
        "background": "#FF7D8473;@img=background_texture_1.png",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "texture_brown_gray",
        "label": "texture_brown_gray",
        "swatch": "#FF776D5D;@img=background_texture_1.png",
        "background": "#FF776D5D;@img=background_texture_1.png",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "texture_black",
    "assets": {
      "film-13-logo-xiaomi_white": {
        "file": "xiaomi/film/13/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "film-13-logo-xiaomi_black": {
        "file": "xiaomi/film/13/xiaomi_black.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 74,
      "top": 76,
      "right": 74,
      "bottom": 322
    },
    "background": "#FF1C1D1F;@img=background_texture_1.png",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 322,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 322,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "topHorCenter",
                "margins": {
                  "top": 128
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "film-13-logo-xiaomi_white",
                    "themeAssets": {
                      "texture_black": "film-13-logo-xiaomi_white",
                      "texture_white": "film-13-logo-xiaomi_black",
                      "texture_rose_gray": "film-13-logo-xiaomi_white",
                      "texture_blue_gray_light": "film-13-logo-xiaomi_white",
                      "texture_blue_gray_dark": "film-13-logo-xiaomi_white",
                      "texture_blue_gray": "film-13-logo-xiaomi_white",
                      "texture_green_dark": "film-13-logo-xiaomi_white",
                      "texture_green_gray_light": "film-13-logo-xiaomi_white",
                      "texture_brown_gray": "film-13-logo-xiaomi_white"
                    },
                    "colorIndex": 3,
                    "width": 168.25,
                    "height": 40,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 36,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 20,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.04,
                    "align": "center",
                    "outline": {
                      "paddingX": 9,
                      "height": 30,
                      "radius": 7,
                      "strokeWidth": 2,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-film:14",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "胶片 · 14",
    "nameEn": "film_14",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "gradient_color_red",
        "label": "gradient_color_red",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "gradient_color_blue_dark",
        "label": "gradient_color_blue_dark",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "gradient_color_blue_light",
        "label": "gradient_color_blue_light",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "gradient_color_red",
    "assets": {
      "film-14-logo-xiaomi_white": {
        "file": "xiaomi/film/14/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 30,
      "top": 109,
      "right": 30,
      "bottom": 120
    },
    "background": "#00000000",
    "bands": [
      {
        "direction": "top",
        "thickness": 109,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 109,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                },
                "font": "misans",
                "size": 22,
                "weight": 500,
                "colorIndex": 0,
                "alpha": 1,
                "tracking": 0.09,
                "align": "left",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "location"
                },
                "font": "misans",
                "size": 22,
                "weight": 500,
                "colorIndex": 0,
                "alpha": 1,
                "tracking": 0.09,
                "align": "left",
                "margins": {},
                "optional": true
              }
            ]
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 120,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 120,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "film-14-logo-xiaomi_white",
                    "themeAssets": {
                      "gradient_color_red": "film-14-logo-xiaomi_white",
                      "gradient_color_blue_dark": "film-14-logo-xiaomi_white",
                      "gradient_color_blue_light": "film-14-logo-xiaomi_white"
                    },
                    "colorIndex": 3,
                    "width": 143.0125,
                    "height": 34,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 30.6,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 20,
                    "weight": 400,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 8,
                      "height": 28,
                      "radius": 6.6,
                      "strokeWidth": 2,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                },
                "font": "misans",
                "size": 22,
                "weight": 500,
                "colorIndex": 0,
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {},
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-film:15",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "胶片 · 15",
    "nameEn": "film_15",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_black",
        "label": "transparent_black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BFCE9238",
          "CE9238"
        ]
      },
      {
        "id": "noise_blur",
        "label": "noise_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": []
      }
    ],
    "defaultTheme": "transparent_black",
    "assets": {
      "film-15-icon_triangle": {
        "file": "xiaomi/film/15/icon_triangle.webp",
        "width": 161,
        "height": 36
      },
      "film-15-logo-xiaomi_CE9238": {
        "file": "xiaomi/film/15/xiaomi_CE9238.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 80,
      "top": 80,
      "right": 80,
      "bottom": 80
    },
    "background": "#FF141414",
    "bands": [
      {
        "direction": "top",
        "thickness": 80,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 80,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "film-15-icon_triangle",
                "width": 80,
                "height": 17.888198757763973,
                "scale": "contain",
                "isBrandLogo": false,
                "margins": {}
              },
              {
                "kind": "text",
                "token": {
                  "kind": "location"
                },
                "font": "misans",
                "size": 17,
                "weight": 500,
                "color": "#FFCE9238",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {},
                "optional": true
              }
            ]
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 80,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 80,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                },
                "font": "misans",
                "size": 17,
                "weight": 500,
                "color": "#FFCE9238",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {},
                "optional": false
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "film-15-logo-xiaomi_CE9238",
                    "themeAssets": {
                      "transparent_black": "film-15-logo-xiaomi_CE9238"
                    },
                    "colorIndex": 3,
                    "width": 109.3625,
                    "height": 26,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 24,
                    "weight": 500,
                    "color": "#FFCE9238",
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 15,
                    "weight": 400,
                    "color": "#FFCE9238",
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 6,
                      "height": 22,
                      "radius": 5.6,
                      "strokeWidth": 1.8,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                },
                "font": "misans",
                "size": 17,
                "weight": 500,
                "color": "#FFCE9238",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {
                  "top": 7
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-film:16",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "胶片 · 16",
    "nameEn": "film_16",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_black",
        "label": "transparent_black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "noise_blur",
        "label": "noise_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": []
      }
    ],
    "defaultTheme": "transparent_black",
    "assets": {
      "film-16-logo-xiaomi_white": {
        "file": "xiaomi/film/16/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 60,
      "top": 60,
      "right": 60,
      "bottom": 240
    },
    "background": "#FF141414",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 240,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": true,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": true,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "film-16-logo-xiaomi_white",
                            "themeAssets": {
                              "transparent_black": "film-16-logo-xiaomi_white",
                              "noise_blur": "film-16-logo-xiaomi_white"
                            },
                            "colorIndex": 3,
                            "width": 143.0125,
                            "height": 34,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 30.6,
                            "weight": 500,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 20,
                            "weight": 400,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0.028,
                            "align": "center",
                            "outline": {
                              "paddingX": 8,
                              "height": 28,
                              "radius": 6.6,
                              "strokeWidth": 2,
                              "strokeColorIndex": 2
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "color": "#FF94352C",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 12
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFCE9238",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 12
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFCE9238",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 12
                        },
                        "optional": true
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "custom"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFCE9238",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 12
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-film:73",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "胶片 · 73",
    "nameEn": "film_73",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "gradient_color_yellow",
        "label": "gradient_color_yellow",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#B2000000",
          "#BF000000",
          "black"
        ]
      },
      {
        "id": "gradient_color_blue_dark",
        "label": "gradient_color_blue_dark",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      },
      {
        "id": "gradient_color_blue_light",
        "label": "gradient_color_blue_light",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "gradient_color_yellow",
    "assets": {
      "film-73-logo-xiaomi_black": {
        "file": "xiaomi/film/73/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "film-73-logo-xiaomi_white": {
        "file": "xiaomi/film/73/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 30,
      "top": 109,
      "right": 30,
      "bottom": 120
    },
    "background": "#00000000",
    "bands": [
      {
        "direction": "top",
        "thickness": 109,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 109,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                },
                "font": "misans",
                "size": 22,
                "weight": 500,
                "colorIndex": 0,
                "alpha": 1,
                "tracking": 0.09,
                "align": "left",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "location"
                },
                "font": "misans",
                "size": 22,
                "weight": 500,
                "colorIndex": 0,
                "alpha": 1,
                "tracking": 0.09,
                "align": "left",
                "margins": {},
                "optional": true
              }
            ]
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 120,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 120,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "film-73-logo-xiaomi_black",
                    "themeAssets": {
                      "gradient_color_yellow": "film-73-logo-xiaomi_black",
                      "gradient_color_blue_dark": "film-73-logo-xiaomi_white",
                      "gradient_color_blue_light": "film-73-logo-xiaomi_white"
                    },
                    "colorIndex": 3,
                    "width": 143.0125,
                    "height": 34,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 30.6,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 20,
                    "weight": 400,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 8,
                      "height": 28,
                      "radius": 6.6,
                      "strokeWidth": 2,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                },
                "font": "misans",
                "size": 22,
                "weight": 500,
                "colorIndex": 0,
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {},
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-film:103",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "胶片 · 103",
    "nameEn": "film_103",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "top_bg",
        "label": "top_bg",
        "swatch": "#FFFFFF",
        "background": "#FFFFFF",
        "foreground": [
          "#FF121212",
          "#4D000000",
          "#BFF4CA32",
          "F4CA32"
        ]
      },
      {
        "id": "bottom_bg",
        "label": "bottom_bg",
        "swatch": "#FFFFFF",
        "background": "#FFFFFF",
        "foreground": [
          "#FF121212",
          "#4D000000",
          "#BFF4CA32",
          "F4CA32"
        ]
      },
      {
        "id": "transparent",
        "label": "transparent",
        "swatch": "#FF121212",
        "background": "#FF121212",
        "foreground": [
          "#FF121212",
          "#4D000000",
          "#BFF4CA32",
          "F4CA32"
        ]
      }
    ],
    "defaultTheme": "transparent",
    "assets": {
      "film-103-logo-xiaomi_F4CA32": {
        "file": "xiaomi/film/103/xiaomi_F4CA32.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 186,
      "right": 0,
      "bottom": 282
    },
    "background": "#FF121212",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 282,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 150,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                },
                "font": "misans",
                "size": 14,
                "weight": 400,
                "color": "#80F4CA32",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {
                  "left": 40
                },
                "optional": false
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "film-103-logo-xiaomi_F4CA32",
                    "themeAssets": {
                      "top_bg": "film-103-logo-xiaomi_F4CA32",
                      "bottom_bg": "film-103-logo-xiaomi_F4CA32",
                      "transparent": "film-103-logo-xiaomi_F4CA32"
                    },
                    "colorIndex": 3,
                    "width": 100.94999999999999,
                    "height": 24,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 21.2,
                    "weight": 500,
                    "color": "#FFF4CA32",
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 13,
                    "weight": 400,
                    "color": "#FFF4CA32",
                    "alpha": 1,
                    "tracking": 0.02,
                    "align": "center",
                    "outline": {
                      "paddingX": 6,
                      "height": 19,
                      "radius": 4.8,
                      "strokeWidth": 1.2,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                },
                "font": "misans",
                "size": 14,
                "weight": 400,
                "color": "#80F4CA32",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {
                  "left": -40
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-leica:1",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 1",
    "nameEn": "leica_1",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "leica-1-logo-xiaomi_black": {
        "file": "xiaomi/leica/1/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "leica-1-logo-xiaomi_white": {
        "file": "xiaomi/leica/1/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "leica-1-leica": {
        "file": "xiaomi/leica/1/leica.webp",
        "width": 240,
        "height": 240
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 144
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 144,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "leica-1-logo-xiaomi_black",
                            "themeAssets": {
                              "white": "leica-1-logo-xiaomi_black",
                              "black": "leica-1-logo-xiaomi_white",
                              "light_blur": "leica-1-logo-xiaomi_black",
                              "dark_blur": "leica-1-logo-xiaomi_white"
                            },
                            "colorIndex": 3,
                            "width": 143.0125,
                            "height": 34,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 30.6,
                            "weight": 500,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 20,
                            "weight": 400,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0.028,
                            "align": "center",
                            "outline": {
                              "paddingX": 8,
                              "height": 28,
                              "radius": 6.6,
                              "strokeWidth": 2,
                              "strokeColorIndex": 4
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0.083,
                        "align": "left",
                        "margins": {
                          "top": 10
                        },
                        "optional": false
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -48
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica-1-leica",
                    "width": 64,
                    "height": 64,
                    "scale": "contain",
                    "isBrandLogo": false,
                    "margins": {}
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {
                      "left": 24
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 30,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0.083,
                        "align": "left",
                        "margins": {
                          "top": 10
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-leica:2",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 2",
    "nameEn": "leica_2",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "leica-2-logo-xiaomi_black": {
        "file": "xiaomi/leica/2/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "leica-2-logo-xiaomi_white": {
        "file": "xiaomi/leica/2/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "leica-2-leica": {
        "file": "xiaomi/leica/2/leica.webp",
        "width": 240,
        "height": 240
      }
    },
    "placement": "with",
    "margins": {
      "left": 80,
      "top": 80,
      "right": 80,
      "bottom": 258
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 80,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 80,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightBottom",
                "margins": {
                  "top": -7
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica-2-logo-xiaomi_black",
                    "themeAssets": {
                      "white": "leica-2-logo-xiaomi_black",
                      "black": "leica-2-logo-xiaomi_white"
                    },
                    "colorIndex": 3,
                    "width": 100.94999999999999,
                    "height": 24,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 21.6,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 13.2,
                    "weight": 400,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 5,
                      "height": 18.5,
                      "radius": 4.4,
                      "strokeWidth": 1.32,
                      "strokeColorIndex": 4
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              }
            ]
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 258,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 250,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica-2-leica",
                    "width": 72,
                    "height": 72,
                    "scale": "contain",
                    "isBrandLogo": false,
                    "margins": {}
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                    },
                    "font": "oxanium",
                    "size": 20,
                    "weight": 500,
                    "color": "#FFE20612",
                    "alpha": 1,
                    "tracking": 0.15,
                    "align": "left",
                    "margins": {
                      "top": 20
                    },
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "location"
                    },
                    "font": "oxanium",
                    "size": 20,
                    "weight": 500,
                    "colorIndex": 2,
                    "alpha": 1,
                    "tracking": 0.15,
                    "align": "left",
                    "margins": {
                      "top": 12
                    },
                    "optional": true
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                    },
                    "font": "oxanium",
                    "size": 20,
                    "weight": 500,
                    "colorIndex": 2,
                    "alpha": 1,
                    "tracking": 0.15,
                    "align": "left",
                    "margins": {
                      "top": 12
                    },
                    "optional": false
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-leica:3",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 3",
    "nameEn": "leica_3",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#33000000",
          "#CC000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#33FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#33000000",
          "#CC000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#33FFFFFF",
          "#CCFFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "leica-3-logo-xiaomi_black": {
        "file": "xiaomi/leica/3/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "leica-3-logo-xiaomi_white": {
        "file": "xiaomi/leica/3/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "leica-3-leica": {
        "file": "xiaomi/leica/3/leica.webp",
        "width": 240,
        "height": 240
      }
    },
    "placement": "with",
    "margins": {
      "left": 48,
      "top": 48,
      "right": 48,
      "bottom": 214
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 214,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "matchWidth": false,
            "matchHeight": false,
            "gravity": "topHorCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "topHorCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "topHorCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "image",
                        "asset": "leica-3-logo-xiaomi_black",
                        "themeAssets": {
                          "white": "leica-3-logo-xiaomi_black",
                          "black": "leica-3-logo-xiaomi_white",
                          "light_blur": "leica-3-logo-xiaomi_black",
                          "dark_blur": "leica-3-logo-xiaomi_white"
                        },
                        "colorIndex": 4,
                        "width": 168.25,
                        "height": 40,
                        "scale": "contain",
                        "isBrandLogo": true,
                        "margins": {
                          "right": 8
                        }
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionNumber}"
                        },
                        "font": "misans",
                        "size": 32,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionName}"
                        },
                        "font": "misans",
                        "size": 23,
                        "weight": 400,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.04,
                        "align": "center",
                        "outline": {
                          "paddingX": 10,
                          "height": 32,
                          "radius": 7,
                          "strokeWidth": 2,
                          "strokeColorIndex": 5
                        },
                        "margins": {},
                        "optional": true
                      },
                      {
                        "kind": "image",
                        "asset": "leica-3-leica",
                        "width": 40,
                        "height": 40,
                        "scale": "contain",
                        "isBrandLogo": false,
                        "margins": {
                          "left": 24
                        }
                      }
                    ]
                  }
                ]
              }
            ]
          },
          {
            "kind": "space",
            "weight": 1
          },
          {
            "kind": "group",
            "orientation": "column",
            "matchWidth": false,
            "matchHeight": false,
            "gravity": "topHorCenter",
            "margins": {
              "top": 45.5
            },
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 400,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "center",
                        "outline": {
                          "width": 108,
                          "height": 60,
                          "radius": 12,
                          "strokeWidth": 2,
                          "strokeColorIndex": 1
                        },
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "S"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 20
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {
                      "left": 50
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 400,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "center",
                        "outline": {
                          "width": 108,
                          "height": 60,
                          "radius": 12,
                          "strokeWidth": 2,
                          "strokeColorIndex": 1
                        },
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "ISO"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 20
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {
                      "left": 50
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 400,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "center",
                        "outline": {
                          "width": 108,
                          "height": 60,
                          "radius": 12,
                          "strokeWidth": 2,
                          "strokeColorIndex": 1
                        },
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "mm"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 20
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {
                      "left": 50
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{aperture}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 400,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "center",
                        "outline": {
                          "width": 108,
                          "height": 60,
                          "radius": 12,
                          "strokeWidth": 2,
                          "strokeColorIndex": 1
                        },
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "F"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 400,
                        "colorIndex": 3,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 20
                        },
                        "optional": false
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-leica:4",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 4",
    "nameEn": "leica_4",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#33000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#33FFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#33000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#33FFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "leica-4-logo-xiaomi_black": {
        "file": "xiaomi/leica/4/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "leica-4-logo-xiaomi_white": {
        "file": "xiaomi/leica/4/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "leica-4-leica": {
        "file": "xiaomi/leica/4/leica.webp",
        "width": 240,
        "height": 240
      }
    },
    "placement": "with",
    "margins": {
      "left": 48,
      "top": 48,
      "right": 48,
      "bottom": 356
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 356,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 356,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "image",
                        "asset": "leica-4-logo-xiaomi_black",
                        "themeAssets": {
                          "white": "leica-4-logo-xiaomi_black",
                          "black": "leica-4-logo-xiaomi_white",
                          "light_blur": "leica-4-logo-xiaomi_black",
                          "dark_blur": "leica-4-logo-xiaomi_white"
                        },
                        "colorIndex": 3,
                        "width": 168.25,
                        "height": 40,
                        "scale": "contain",
                        "isBrandLogo": true,
                        "margins": {
                          "right": 8
                        }
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionNumber}"
                        },
                        "font": "misans",
                        "size": 32,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionName}"
                        },
                        "font": "misans",
                        "size": 23,
                        "weight": 400,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.04,
                        "align": "center",
                        "outline": {
                          "paddingX": 10,
                          "height": 32,
                          "radius": 7,
                          "strokeWidth": 2,
                          "strokeColorIndex": 4
                        },
                        "margins": {},
                        "optional": true
                      },
                      {
                        "kind": "image",
                        "asset": "leica-4-leica",
                        "width": 40,
                        "height": 40,
                        "scale": "contain",
                        "isBrandLogo": false,
                        "margins": {
                          "left": 24
                        }
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {
                      "top": 36
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm"
                        },
                        "font": "misans",
                        "size": 24,
                        "weight": 500,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=f/@{aperture}"
                        },
                        "font": "misans",
                        "size": 24,
                        "weight": 500,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "left": 60
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}s"
                        },
                        "font": "misans",
                        "size": 24,
                        "weight": 500,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "left": 60
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 24,
                        "weight": 500,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "left": 60
                        },
                        "optional": false
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-leica:90",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 90",
    "nameEn": "leica_90",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#80000000",
          "#FFE20612",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#80FFFFFF",
          "#BFFFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "leica-90-logo-xiaomi_black": {
        "file": "xiaomi/leica/90/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "leica-90-logo-xiaomi_white": {
        "file": "xiaomi/leica/90/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "leica-90-leica": {
        "file": "xiaomi/leica/90/leica.webp",
        "width": 240,
        "height": 240
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 480
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 480,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 480,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 80
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "image",
                        "asset": "leica-90-logo-xiaomi_black",
                        "themeAssets": {
                          "white": "leica-90-logo-xiaomi_black",
                          "black": "leica-90-logo-xiaomi_white"
                        },
                        "colorIndex": 3,
                        "width": 100.94999999999999,
                        "height": 24,
                        "scale": "contain",
                        "isBrandLogo": true,
                        "margins": {
                          "right": 8
                        }
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionNumber}"
                        },
                        "font": "misans",
                        "size": 21.6,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@model=@{versionName}"
                        },
                        "font": "misans",
                        "size": 13.2,
                        "weight": 400,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0.028,
                        "align": "center",
                        "outline": {
                          "paddingX": 5,
                          "height": 20,
                          "radius": 4,
                          "strokeWidth": 1,
                          "strokeColorIndex": 4
                        },
                        "margins": {},
                        "optional": true
                      }
                    ]
                  },
                  {
                    "kind": "image",
                    "asset": "leica-90-leica",
                    "width": 72,
                    "height": 72,
                    "scale": "contain",
                    "isBrandLogo": false,
                    "margins": {
                      "top": 80
                    }
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {
                      "top": 92
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFE20612",
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=f/@{aperture}"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFE20612",
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {
                          "left": 20
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}s"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFE20612",
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {
                          "left": 20
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=ISO@{iso}"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFE20612",
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {
                          "left": 20
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {
                      "top": 7
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "colorIndex": 1,
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {},
                        "optional": true
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {
                      "top": 7
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "colorIndex": 1,
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-leica:91",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 91",
    "nameEn": "leica_91",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "leica-91-logo-xiaomi_white": {
        "file": "xiaomi/leica/91/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "leica-91-leica": {
        "file": "xiaomi/leica/91/leica.webp",
        "width": 240,
        "height": 240
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "width": 1080,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 80,
                  "top": 11
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "height": 30,
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "leica-91-logo-xiaomi_white",
                            "themeAssets": {
                              "transparent_white": "leica-91-logo-xiaomi_white"
                            },
                            "colorIndex": 0,
                            "width": 100.94999999999999,
                            "height": 24,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 21.6,
                            "weight": 500,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 13.2,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.028,
                            "align": "center",
                            "outline": {
                              "paddingX": 5,
                              "height": 20,
                              "radius": 4,
                              "strokeWidth": 1,
                              "strokeColorIndex": 1
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "group",
                        "orientation": "column",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {
                          "top": 59
                        },
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_yyyy_MM_dd"
                            },
                            "font": "lg1056",
                            "size": 20,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.15,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_HH_mm"
                            },
                            "font": "lg1056",
                            "size": 20,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.15,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -80,
                  "top": -44
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica-91-leica",
                    "width": 60,
                    "height": 60,
                    "scale": "contain",
                    "isBrandLogo": false,
                    "margins": {}
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightTop",
                "margins": {
                  "top": 120.5
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "height": 55,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "topHorCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {},
                        "optional": true
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "height": 58,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightTop",
                    "margins": {
                      "left": -80,
                      "top": 2
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  ISO @{iso}"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {
                          "top": -1
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=f/@{aperture}  @{speed}s"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (center_top) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using center_top layout"
      ]
    }
  },
  {
    "id": "xiaomi-leica:92",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 92",
    "nameEn": "leica_92",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#80000000",
          "#BF000000",
          "black"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#80FFFFFF",
          "#BFFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "leica-92-logo-xiaomi_black": {
        "file": "xiaomi/leica/92/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "leica-92-logo-xiaomi_white": {
        "file": "xiaomi/leica/92/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "leica-92-leica": {
        "file": "xiaomi/leica/92/leica.webp",
        "width": 240,
        "height": 240
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 188,
      "right": 0,
      "bottom": 194
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 188,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 188,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {
                  "left": 80,
                  "top": 104
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica-92-logo-xiaomi_black",
                    "themeAssets": {
                      "white": "leica-92-logo-xiaomi_black",
                      "black": "leica-92-logo-xiaomi_white"
                    },
                    "colorIndex": 3,
                    "width": 100.94999999999999,
                    "height": 24,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 21.6,
                    "weight": 500,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 13.2,
                    "weight": 400,
                    "colorIndex": 0,
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 5,
                      "height": 20,
                      "radius": 4,
                      "strokeWidth": 1,
                      "strokeColorIndex": 2
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightTop",
                "margins": {
                  "left": -80,
                  "top": 108
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "height": 16,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "colorIndex": 1,
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 194,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 194,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftTop",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 80
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica-92-leica",
                    "width": 72,
                    "height": 72,
                    "scale": "contain",
                    "isBrandLogo": false,
                    "margins": {}
                  },
                  {
                    "kind": "space",
                    "width": 40
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "height": 40,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "column",
                        "height": 16,
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@exif=@{focal}mm"
                            },
                            "font": "lg1056",
                            "size": 20,
                            "weight": 400,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0.15,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          }
                        ]
                      },
                      {
                        "kind": "group",
                        "orientation": "column",
                        "height": 16,
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {
                          "top": 8
                        },
                        "children": [
                          {
                            "kind": "group",
                            "orientation": "row",
                            "matchWidth": false,
                            "matchHeight": false,
                            "gravity": "leftVerticalCenter",
                            "margins": {},
                            "children": [
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "literal",
                                  "value": "F"
                                },
                                "font": "lg1056",
                                "size": 20,
                                "weight": 400,
                                "colorIndex": 1,
                                "alpha": 1,
                                "tracking": 0.15,
                                "align": "left",
                                "margins": {},
                                "optional": false
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "template",
                                  "value": "@exif=@{aperture}"
                                },
                                "font": "lg1056",
                                "size": 20,
                                "weight": 400,
                                "colorIndex": 0,
                                "alpha": 1,
                                "tracking": 0.15,
                                "align": "left",
                                "margins": {
                                  "left": 8
                                },
                                "optional": false
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "literal",
                                  "value": "S"
                                },
                                "font": "lg1056",
                                "size": 20,
                                "weight": 400,
                                "colorIndex": 1,
                                "alpha": 1,
                                "tracking": 0.15,
                                "align": "left",
                                "margins": {
                                  "left": 20
                                },
                                "optional": false
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "template",
                                  "value": "@exif=@{speed}"
                                },
                                "font": "lg1056",
                                "size": 20,
                                "weight": 400,
                                "colorIndex": 0,
                                "alpha": 1,
                                "tracking": 0.15,
                                "align": "left",
                                "margins": {
                                  "left": 8
                                },
                                "optional": false
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "literal",
                                  "value": "ISO"
                                },
                                "font": "lg1056",
                                "size": 20,
                                "weight": 400,
                                "colorIndex": 1,
                                "alpha": 1,
                                "tracking": 0.15,
                                "align": "left",
                                "margins": {
                                  "left": 20
                                },
                                "optional": false
                              },
                              {
                                "kind": "text",
                                "token": {
                                  "kind": "template",
                                  "value": "@exif=@{iso}"
                                },
                                "font": "lg1056",
                                "size": 20,
                                "weight": 400,
                                "colorIndex": 0,
                                "alpha": 1,
                                "tracking": 0.15,
                                "align": "left",
                                "margins": {
                                  "left": 8
                                },
                                "optional": false
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -80
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "height": 16,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "rightTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "lg1056",
                        "size": 20,
                        "weight": 400,
                        "colorIndex": 1,
                        "alpha": 1,
                        "tracking": 0.15,
                        "align": "left",
                        "margins": {},
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-leica:104",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 104",
    "nameEn": "leica_104",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "white",
        "label": "white",
        "swatch": "#FFFFFFFF",
        "background": "#FFFFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      },
      {
        "id": "light_blur",
        "label": "light_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#80000000",
          "black",
          "#BF000000"
        ]
      },
      {
        "id": "dark_blur",
        "label": "dark_blur",
        "swatch": "#00000000",
        "background": "#00000000",
        "foreground": [
          "#FFFFFFFF",
          "#B2FFFFFF",
          "#80FFFFFF",
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "white",
    "assets": {
      "leica-104-logo-xiaomi_black": {
        "file": "xiaomi/leica/104/xiaomi_black.webp",
        "width": 673,
        "height": 160
      },
      "leica-104-logo-xiaomi_white": {
        "file": "xiaomi/leica/104/xiaomi_white.webp",
        "width": 673,
        "height": 160
      },
      "leica-104-leica": {
        "file": "xiaomi/leica/104/leica.webp",
        "width": 240,
        "height": 240
      }
    },
    "placement": "out",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 136
    },
    "background": "#FFFFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 136,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "leica-104-logo-xiaomi_black",
                            "themeAssets": {
                              "white": "leica-104-logo-xiaomi_black",
                              "black": "leica-104-logo-xiaomi_white",
                              "light_blur": "leica-104-logo-xiaomi_black",
                              "dark_blur": "leica-104-logo-xiaomi_white"
                            },
                            "colorIndex": 3,
                            "width": 117.77499999999999,
                            "height": 28,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 25,
                            "weight": 500,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 16,
                            "weight": 400,
                            "colorIndex": 0,
                            "alpha": 1,
                            "tracking": 0.032,
                            "align": "center",
                            "outline": {
                              "paddingX": 6,
                              "height": 23,
                              "radius": 5.6,
                              "strokeWidth": 1.8,
                              "strokeColorIndex": 4
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 500,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0.05,
                        "align": "left",
                        "margins": {
                          "top": 10
                        },
                        "optional": false
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -48
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica-104-leica",
                    "width": 60,
                    "height": 60,
                    "scale": "contain",
                    "isBrandLogo": false,
                    "margins": {}
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {
                      "left": 24,
                      "top": 2
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                        },
                        "font": "misans",
                        "size": 22,
                        "weight": 500,
                        "colorIndex": 0,
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "location"
                        },
                        "font": "misans",
                        "size": 20,
                        "weight": 500,
                        "colorIndex": 2,
                        "alpha": 1,
                        "tracking": 0.05,
                        "align": "left",
                        "margins": {
                          "top": 12
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-leica:111",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "徕卡 · 111",
    "nameEn": "leica_111",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "black",
        "label": "black",
        "swatch": "#FF141414",
        "background": "#FF141414",
        "foreground": [
          "#FF141414",
          "#FFE6A54F",
          "E6A54F",
          "#BFE6A54F"
        ]
      }
    ],
    "defaultTheme": "black",
    "assets": {
      "leica-111-leica": {
        "file": "xiaomi/leica/111/leica.webp",
        "width": 240,
        "height": 240
      },
      "leica-111-logo-xiaomi_E6A54F": {
        "file": "xiaomi/leica/111/xiaomi_E6A54F.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "with",
    "margins": {
      "left": 80,
      "top": 80,
      "right": 80,
      "bottom": 302
    },
    "background": "#FF141414",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 302,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "height": 302,
            "matchWidth": true,
            "matchHeight": false,
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "leica-111-leica",
                "width": 74,
                "height": 74,
                "scale": "contain",
                "isBrandLogo": false,
                "gravity": "topHorCenter",
                "margins": {
                  "top": 50
                }
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "topHorCenter",
                "margins": {
                  "top": 33.5
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica-111-logo-xiaomi_E6A54F",
                    "themeAssets": {
                      "black": "leica-111-logo-xiaomi_E6A54F"
                    },
                    "colorIndex": 2,
                    "width": 117.77499999999999,
                    "height": 28,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 25,
                    "weight": 500,
                    "colorIndex": 1,
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 16,
                    "weight": 400,
                    "colorIndex": 1,
                    "alpha": 1,
                    "tracking": 0.032,
                    "align": "center",
                    "outline": {
                      "paddingX": 6,
                      "height": 23,
                      "radius": 5.6,
                      "strokeWidth": 1.8,
                      "strokeColorIndex": 3
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                },
                "font": "lg1056",
                "size": 17,
                "weight": 400,
                "color": "#66E6A54F",
                "alpha": 1,
                "tracking": 0.2,
                "align": "left",
                "margins": {
                  "top": 9.5
                },
                "optional": false,
                "gravity": "topHorCenter"
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from Xiaomi camera config; needs visual audit."
    }
  },
  {
    "id": "xiaomi-standard:6",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "小米标准 · 6",
    "nameEn": "standard_6",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BFFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "standard-6-logo-xiaomi_white": {
        "file": "xiaomi/standard/6/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "width": 1080,
            "gravity": "leftBottom",
            "margins": {
              "bottom": 37
            },
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "space",
                        "width": 20
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "standard-6-logo-xiaomi_white",
                            "themeAssets": {
                              "transparent_white": "standard-6-logo-xiaomi_white"
                            },
                            "colorIndex": 3,
                            "width": 134.6,
                            "height": 32,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 27,
                            "weight": 500,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 17.6,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.032,
                            "align": "center",
                            "outline": {
                              "paddingX": 7,
                              "height": 25,
                              "radius": 6.4,
                              "strokeWidth": 1.8,
                              "strokeColorIndex": 2
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "custom"
                        },
                        "font": "misans",
                        "size": 26,
                        "weight": 400,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 10
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -48
                },
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                    },
                    "font": "misans",
                    "size": 26,
                    "weight": 400,
                    "color": "#FFFFFFFF",
                    "alpha": 1,
                    "tracking": 0.083,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom layout",
        "3 anchor presets in config; default bottom used"
      ]
    }
  },
  {
    "id": "xiaomi-standard:7",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "小米标准 · 7",
    "nameEn": "standard_7",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "standard-7-logo-xiaomi_white": {
        "file": "xiaomi/standard/7/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "leftBottom",
            "margins": {
              "left": 48,
              "bottom": 30
            },
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "standard-7-logo-xiaomi_white",
                    "themeAssets": {
                      "transparent_white": "standard-7-logo-xiaomi_white"
                    },
                    "colorIndex": 2,
                    "width": 134.6,
                    "height": 32,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 27,
                    "weight": 500,
                    "color": "#FFFFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 17.6,
                    "weight": 400,
                    "color": "#FFFFFFFF",
                    "alpha": 1,
                    "tracking": 0.032,
                    "align": "center",
                    "outline": {
                      "paddingX": 7,
                      "height": 25,
                      "radius": 6.4,
                      "strokeWidth": 1.8,
                      "strokeColorIndex": 3
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "custom"
                },
                "font": "misans",
                "size": 26,
                "weight": 400,
                "color": "#FFFFFFFF",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "margins": {
                  "top": 4
                },
                "optional": true
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                },
                "font": "misans",
                "size": 26,
                "weight": 400,
                "color": "#FFFFFFFF",
                "alpha": 1,
                "tracking": 0.083,
                "align": "left",
                "margins": {
                  "top": 4
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom_left) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom_left layout",
        "9 anchor presets in config; default bottom_left used"
      ]
    }
  },
  {
    "id": "xiaomi-standard:8",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "小米标准 · 8",
    "nameEn": "standard_8",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "white",
          "#BFFFFFFF"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "standard-8-logo-xiaomi_white": {
        "file": "xiaomi/standard/8/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "leftBottom",
            "margins": {
              "left": 48,
              "bottom": 40
            },
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "image",
                    "asset": "standard-8-logo-xiaomi_white",
                    "themeAssets": {
                      "transparent_white": "standard-8-logo-xiaomi_white"
                    },
                    "colorIndex": 2,
                    "width": 100.94999999999999,
                    "height": 24,
                    "scale": "contain",
                    "isBrandLogo": true,
                    "margins": {
                      "right": 8
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionNumber}"
                    },
                    "font": "misans",
                    "size": 21.6,
                    "weight": 500,
                    "color": "#FFFFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@model=@{versionName}"
                    },
                    "font": "misans",
                    "size": 13.2,
                    "weight": 400,
                    "color": "#FFFFFFFF",
                    "alpha": 1,
                    "tracking": 0.028,
                    "align": "center",
                    "outline": {
                      "paddingX": 5,
                      "height": 20,
                      "radius": 4,
                      "strokeWidth": 1,
                      "strokeColorIndex": 3
                    },
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}"
                },
                "font": "oxanium",
                "size": 20,
                "weight": 500,
                "color": "#FFFFFFFF",
                "alpha": 1,
                "tracking": 0.15,
                "align": "left",
                "margins": {
                  "top": 10
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "location"
                },
                "font": "oxanium",
                "size": 20,
                "weight": 500,
                "color": "#FFFFFFFF",
                "alpha": 1,
                "tracking": 0.15,
                "align": "left",
                "margins": {
                  "top": 10
                },
                "optional": true
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                },
                "font": "oxanium",
                "size": 20,
                "weight": 500,
                "color": "#FFFFFFFF",
                "alpha": 1,
                "tracking": 0.15,
                "align": "left",
                "margins": {
                  "top": 10
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom_left) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom_left layout",
        "9 anchor presets in config; default bottom_left used"
      ]
    }
  },
  {
    "id": "xiaomi-standard:107",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "小米标准 · 107",
    "nameEn": "standard_107",
    "referenceWidth": 1080,
    "themes": [
      {
        "id": "transparent_white",
        "label": "transparent_white",
        "swatch": "#00FFFFFF",
        "background": "#00FFFFFF",
        "foreground": [
          "#FF000000",
          "#4D000000",
          "#BFFFFFFF",
          "white"
        ]
      }
    ],
    "defaultTheme": "transparent_white",
    "assets": {
      "standard-107-logo-xiaomi_white": {
        "file": "xiaomi/standard/107/xiaomi_white.webp",
        "width": 673,
        "height": 160
      }
    },
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "width": 1080,
            "gravity": "leftBottom",
            "margins": {
              "bottom": 30
            },
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 48
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "space",
                        "width": 20
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "image",
                            "asset": "standard-107-logo-xiaomi_white",
                            "themeAssets": {
                              "transparent_white": "standard-107-logo-xiaomi_white"
                            },
                            "colorIndex": 3,
                            "width": 107.68,
                            "height": 25.6,
                            "scale": "contain",
                            "isBrandLogo": true,
                            "margins": {
                              "right": 8
                            }
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionNumber}"
                            },
                            "font": "misans",
                            "size": 22.2,
                            "weight": 500,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@model=@{versionName}"
                            },
                            "font": "misans",
                            "size": 14,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.02,
                            "align": "center",
                            "outline": {
                              "paddingX": 7.2,
                              "height": 21,
                              "radius": 5.1,
                              "strokeWidth": 1.3,
                              "strokeColorIndex": 2
                            },
                            "margins": {},
                            "optional": true
                          }
                        ]
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "custom"
                        },
                        "font": "misans",
                        "size": 20.8,
                        "weight": 400,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {
                          "top": 8
                        },
                        "optional": true
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "rightVerticalCenter",
                "margins": {
                  "left": -48
                },
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@time=@wm_time_yyyy_MM_dd_HH_mm"
                    },
                    "font": "misans",
                    "size": 20.8,
                    "weight": 400,
                    "color": "#FFFFFFFF",
                    "alpha": 1,
                    "tracking": 0.02,
                    "align": "left",
                    "margins": {},
                    "optional": false
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom layout",
        "3 anchor presets in config; default bottom used"
      ]
    }
  },
  {
    "id": "xiaomi-tag:9",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "标签 · 9",
    "nameEn": "tag_9",
    "referenceWidth": 1080,
    "themes": [],
    "defaultTheme": "default",
    "assets": {},
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "gravity": "leftBottom",
            "margins": {
              "left": 72,
              "bottom": 72
            },
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_HH_mm"
                        },
                        "font": "misans",
                        "size": 96,
                        "weight": 400,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "group",
                        "orientation": "column",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftVerticalCenter",
                        "margins": {
                          "left": 33
                        },
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_yyyy_MM_dd"
                            },
                            "font": "misans",
                            "size": 38,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_week"
                            },
                            "font": "misans",
                            "size": 38,
                            "weight": 400,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@location=location_address_list;@locationLimit=0.54;@locationMaxLines=2"
                    },
                    "font": "misans",
                    "size": 38,
                    "weight": 400,
                    "color": "#FFFFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "align": "left",
                    "margins": {
                      "top": 20
                    },
                    "optional": true
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom_left) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom_left layout",
        "9 anchor presets in config; default bottom_left used"
      ]
    }
  },
  {
    "id": "xiaomi-tag:10",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "标签 · 10",
    "nameEn": "tag_10",
    "referenceWidth": 1080,
    "themes": [],
    "defaultTheme": "default",
    "assets": {},
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "leftBottom",
            "margins": {
              "left": 72,
              "bottom": 72
            },
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "width": 226,
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "打卡记录"
                        },
                        "font": "misans",
                        "size": 44,
                        "weight": 400,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0.022,
                        "align": "center",
                        "outline": {
                          "paddingY": 6,
                          "radius": 10,
                          "strokeWidth": 0
                        },
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_HH_mm"
                        },
                        "font": "misans",
                        "size": 62,
                        "weight": 500,
                        "color": "#FFFFFF",
                        "alpha": 1,
                        "tracking": 0,
                        "align": "center",
                        "outline": {
                          "paddingY": 6,
                          "strokeWidth": 0
                        },
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "group",
                    "orientation": "row",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftVerticalCenter",
                    "margins": {
                      "top": 20
                    },
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_yyyy_MM_dd"
                        },
                        "font": "misans",
                        "size": 30,
                        "weight": 300,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0.066,
                        "align": "left",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@time=@wm_time_week"
                        },
                        "font": "misans",
                        "size": 30,
                        "weight": 300,
                        "color": "#FFFFFFFF",
                        "alpha": 1,
                        "tracking": 0.066,
                        "align": "left",
                        "margins": {
                          "left": 20
                        },
                        "optional": false
                      }
                    ]
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@location=location_address_list;@locationLimit=0.444;@locationMaxLines=2;"
                },
                "font": "misans",
                "size": 30,
                "weight": 300,
                "color": "#FFFFFFFF",
                "alpha": 1,
                "tracking": 0.066,
                "align": "left",
                "margins": {
                  "top": 4
                },
                "optional": true
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom_left) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom_left layout",
        "9 anchor presets in config; default bottom_left used"
      ]
    }
  },
  {
    "id": "xiaomi-tag:11",
    "vendor": "xiaomi",
    "family": "小米相机",
    "name": "标签 · 11",
    "nameEn": "tag_11",
    "referenceWidth": 1080,
    "themes": [],
    "defaultTheme": "default",
    "assets": {},
    "placement": "in",
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "transparentBackground": true,
    "bands": [
      {
        "direction": "overlay",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "gravity": "leftBottom",
            "margins": {
              "left": 72,
              "bottom": 72
            },
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "matchWidth": false,
                "matchHeight": false,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "matchWidth": false,
                    "matchHeight": false,
                    "gravity": "leftTop",
                    "margins": {},
                    "children": [
                      {
                        "kind": "group",
                        "orientation": "column",
                        "width": 172,
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftTop",
                        "margins": {},
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_MM"
                            },
                            "font": "misans",
                            "size": 44,
                            "weight": 400,
                            "color": "#FFFFFF",
                            "alpha": 1,
                            "tracking": 0.022,
                            "align": "center",
                            "outline": {
                              "paddingY": 3,
                              "radius": 20,
                              "strokeWidth": 0
                            },
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_dd"
                            },
                            "font": "misans",
                            "size": 72,
                            "weight": 500,
                            "color": "#FFFFFF",
                            "alpha": 1,
                            "tracking": 0,
                            "align": "center",
                            "outline": {
                              "paddingY": 3,
                              "strokeWidth": 0
                            },
                            "margins": {},
                            "optional": false
                          }
                        ]
                      },
                      {
                        "kind": "group",
                        "orientation": "row",
                        "matchWidth": false,
                        "matchHeight": false,
                        "gravity": "leftVerticalCenter",
                        "margins": {
                          "top": 20
                        },
                        "children": [
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_yyyy_MM_dd"
                            },
                            "font": "misans",
                            "size": 30,
                            "weight": 300,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.066,
                            "align": "left",
                            "margins": {},
                            "optional": false
                          },
                          {
                            "kind": "text",
                            "token": {
                              "kind": "template",
                              "value": "@time=@wm_time_week"
                            },
                            "font": "misans",
                            "size": 30,
                            "weight": 300,
                            "color": "#FFFFFFFF",
                            "alpha": 1,
                            "tracking": 0.066,
                            "align": "left",
                            "margins": {
                              "left": 20
                            },
                            "optional": false
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@location=location_address_list;@locationLimit=0.444;@locationMaxLines=2"
                    },
                    "font": "misans",
                    "size": 30,
                    "weight": 300,
                    "color": "#FFFFFFFF",
                    "alpha": 1,
                    "tracking": 0.066,
                    "align": "left",
                    "margins": {
                      "top": 4
                    },
                    "optional": true
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "audit": {
      "status": "approx",
      "notes": "In-style overlay (bottom_left) converted from Xiaomi config; pending visual audit.",
      "unsupported": [
        "in-style overlay anchored using bottom_left layout",
        "9 anchor presets in config; default bottom_left used"
      ]
    }
  }
] as StockStyle[]
