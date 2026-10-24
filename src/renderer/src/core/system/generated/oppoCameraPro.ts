import type { StockStyle } from '../ir'

export const OPPO_CAMERAPRO_STYLES = [
  {
    "id": "oppo-camerapro:gr_style_1",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "理光 GR · 上框",
    "nameEn": "RICOH GR · TOP",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 8,
      "top": 95,
      "right": 8,
      "bottom": 70
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 95,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 340,
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 12,
                "weight": 600,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0.04,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {},
                "optional": true
              }
            ]
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 70,
        "children": [
          {
            "kind": "image",
            "asset": "gr_style_1-realme_gr_logo_dark",
            "width": 75,
            "height": 23,
            "scale": "contain",
            "gravity": "center",
            "margins": {},
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "gr_style_1-realme_gr_logo_dark": {
        "file": "oppo/camerapro/realme_gr_logo_dark.webp",
        "width": 75,
        "height": 23
      }
    },
    "audit": {
      "status": "approx",
      "unsupported": [
        "FZLTZCHK substituted with SysSans"
      ],
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  },
  {
    "id": "oppo-camerapro:gr_style_2",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "理光 GR · 页脚",
    "nameEn": "RICOH GR · FOOTER",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 8,
      "top": 8,
      "right": 8,
      "bottom": 70
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 70,
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "group",
                "orientation": "column",
                "width": 230,
                "gravity": "leftBottom",
                "margins": {
                  "left": 4,
                  "top": 21
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "gr_style_2-realme_gr_logo_dark",
                    "width": 75,
                    "height": 23,
                    "scale": "contain",
                    "gravity": "leftBottom",
                    "margins": {},
                    "alpha": 1
                  }
                ]
              },
              {
                "kind": "space",
                "weight": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 9,
                "weight": 600,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0.04,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "rightBottom",
                "margins": {
                  "right": 10
                },
                "optional": true
              }
            ],
            "matchWidth": true
          }
        ]
      }
    ],
    "assets": {
      "gr_style_2-realme_gr_logo_dark": {
        "file": "oppo/camerapro/realme_gr_logo_dark.webp",
        "width": 75,
        "height": 23
      }
    },
    "audit": {
      "status": "approx",
      "unsupported": [
        "FZLTZCHK substituted with SysSans"
      ],
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  },
  {
    "id": "oppo-camerapro:gr_style_3",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "理光 GR · 下框",
    "nameEn": "RICOH GR · BOTTOM",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 90
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 90,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 340,
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "gr_style_3-realme_gr_logo_dark",
                "width": 72,
                "height": 24,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {
                  "bottom": 20
                },
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 9,
                "weight": 600,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0.04,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "horCenter",
                "margins": {},
                "optional": true
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "gr_style_3-realme_gr_logo_dark": {
        "file": "oppo/camerapro/realme_gr_logo_dark.webp",
        "width": 72,
        "height": 24
      }
    },
    "audit": {
      "status": "approx",
      "unsupported": [
        "FZLTZCHK substituted with SysSans"
      ],
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  },
  {
    "id": "oppo-camerapro:gr_style_4",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "理光 GR · 叠加",
    "nameEn": "RICOH GR · OVERLAY",
    "placement": "overlay",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "bands": [
      {
        "direction": "overlay",
        "thickness": 54,
        "gravity": "centerBottom",
        "offset": {
          "x": 0,
          "y": 20
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "space",
                "weight": 1
              },
              {
                "kind": "image",
                "asset": "gr_style_4-realme_gr_logo_white",
                "width": 70,
                "height": 22,
                "scale": "contain",
                "gravity": "topHorCenter",
                "margins": {},
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 9,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.04,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 19
                },
                "optional": true,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              }
            ],
            "matchWidth": true
          }
        ]
      }
    ],
    "assets": {
      "gr_style_4-realme_gr_logo_white": {
        "file": "oppo/camerapro/realme_gr_logo_white.webp",
        "width": 70,
        "height": 22
      }
    },
    "audit": {
      "status": "approx",
      "unsupported": [
        "FZLTZCHK substituted with SysSans"
      ],
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  },
  {
    "id": "oppo-camerapro:gr_style_5",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "理光 GR · 参数边栏",
    "nameEn": "RICOH GR · SIDEBAR",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 6,
      "top": 6,
      "right": 120,
      "bottom": 6
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "right",
        "thickness": 120,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 8,
                "weight": 600,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0.04,
                "lineHeight": 9.6,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {},
                "optional": true
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 83
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Style"
                        },
                        "font": "avenir",
                        "size": 5,
                        "weight": 700,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "avenir",
                    "size": 5,
                    "weight": 700,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 8
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 6
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Saturation"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Hue"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Tone"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Contrast"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Highlight"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Shadow"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Sharpness"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "High / Low"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Clarity"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Grain Intensity"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 120,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 31,
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "width": 41,
                    "gravity": "leftVerticalCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "Grain Size"
                        },
                        "font": "sys",
                        "size": 5,
                        "weight": 400,
                        "color": "#848484",
                        "alpha": 1,
                        "tracking": 0.04,
                        "lineHeight": 6.4,
                        "width": 41,
                        "align": "left",
                        "gravity": "leftVerticalCenter",
                        "margins": {},
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "—"
                    },
                    "font": "sys",
                    "size": 5,
                    "weight": 400,
                    "color": "#848484",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 6.4,
                    "align": "left",
                    "gravity": "rightVerticalCenter",
                    "margins": {
                      "left": 11
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "image",
                "asset": "gr_style_5-realme_gr_logo_dark",
                "width": 56,
                "height": 18,
                "scale": "contain",
                "gravity": "bottomHorCenter",
                "margins": {
                  "top": 88
                },
                "alpha": 1
              }
            ],
            "matchWidth": true
          }
        ]
      }
    ],
    "assets": {
      "gr_style_5-realme_gr_logo_dark": {
        "file": "oppo/camerapro/realme_gr_logo_dark.webp",
        "width": 56,
        "height": 18
      }
    },
    "audit": {
      "status": "approx",
      "unsupported": [
        "FZLTZCHK substituted with SysSans",
        "Gilroy substituted with Avenir",
        "GR filter values are not stored in EXIF; rendered as dash placeholders"
      ],
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  },
  {
    "id": "oppo-camerapro:tone_frame_style_1",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "色调边框 · 上",
    "nameEn": "TONE FRAME · TOP",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 210,
      "right": 0,
      "bottom": 0
    },
    "background": "#425A66",
    "bands": [
      {
        "direction": "top",
        "thickness": 210,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 320,
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "literal",
                  "value": "Every moment feels like"
                },
                "font": "sys",
                "size": 14,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 0.58,
                "tracking": 0.01,
                "lineHeight": 18,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 9,
                "weight": 400,
                "color": "#FFFFFF",
                "alpha": 0.42,
                "tracking": 0.04,
                "lineHeight": 12,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 5
                },
                "optional": true
              }
            ]
          }
        ]
      }
    ],
    "assets": {},
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  },
  {
    "id": "oppo-camerapro:tone_gallery_frame_style_1",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "色调边框 · 页脚",
    "nameEn": "TONE FRAME · FOOTER",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 14,
      "top": 14,
      "right": 14,
      "bottom": 144
    },
    "background": "#436A62",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 144,
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 332,
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "literal",
                  "value": "Make it last."
                },
                "font": "misans",
                "size": 10,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 0.94,
                "tracking": 0.18,
                "lineHeight": 13.5,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {},
                "optional": false
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "horCenter",
                "margins": {
                  "top": 7
                },
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "Captured with "
                    },
                    "font": "misans",
                    "size": 7.5,
                    "weight": 400,
                    "color": "#FFFFFF",
                    "alpha": 0.56,
                    "tracking": 0.03,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "model"
                    },
                    "font": "misans",
                    "size": 7.5,
                    "weight": 400,
                    "color": "#FFFFFF",
                    "alpha": 0.56,
                    "tracking": 0.03,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": true
                  }
                ]
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "horCenter",
                "margins": {
                  "top": 1
                },
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@exif=F@{aperture}"
                    },
                    "font": "misans",
                    "size": 7.5,
                    "weight": 400,
                    "color": "#FFFFFF",
                    "alpha": 0.56,
                    "tracking": 0.03,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": " · "
                    },
                    "font": "misans",
                    "size": 7.5,
                    "weight": 400,
                    "color": "#FFFFFF",
                    "alpha": 0.56,
                    "tracking": 0.03,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@exif=@{speed}s"
                    },
                    "font": "misans",
                    "size": 7.5,
                    "weight": 400,
                    "color": "#FFFFFF",
                    "alpha": 0.56,
                    "tracking": 0.03,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": " · "
                    },
                    "font": "misans",
                    "size": 7.5,
                    "weight": 400,
                    "color": "#FFFFFF",
                    "alpha": 0.56,
                    "tracking": 0.03,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
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
                    "size": 7.5,
                    "weight": 400,
                    "color": "#FFFFFF",
                    "alpha": 0.56,
                    "tracking": 0.03,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "template",
                      "value": "@exif=@{iso}"
                    },
                    "font": "misans",
                    "size": 7.5,
                    "weight": 400,
                    "color": "#FFFFFF",
                    "alpha": 0.56,
                    "tracking": 0.03,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2
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
    "assets": {},
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  },
  {
    "id": "oppo-camerapro:leica_live_moment_style_1",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "徕卡动态",
    "nameEn": "LEICA LIVE MOMENT",
    "placement": "overlay",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "bands": [
      {
        "direction": "overlay",
        "thickness": 64,
        "gravity": "centerBottom",
        "offset": {
          "x": 0,
          "y": 6
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 180,
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "leica_live_moment_style_1-leica_live_moment_logo",
                "width": 18,
                "height": 18,
                "scale": "contain",
                "gravity": "topHorCenter",
                "margins": {},
                "alpha": 1
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "horCenter",
                "margins": {
                  "top": 4
                },
                "children": [
                  {
                    "kind": "image",
                    "asset": "leica_live_moment_style_1-leica_live_moment_corner_left",
                    "width": 4,
                    "height": 10,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "literal",
                      "value": "Live Moment"
                    },
                    "font": "misans",
                    "size": 9,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0.05,
                    "lineHeight": 10,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 3,
                      "right": 3
                    },
                    "optional": false,
                    "shadow": {
                      "color": "#80000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "leica_live_moment_style_1-leica_live_moment_corner_right",
                    "width": 4,
                    "height": 10,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "misans",
                "size": 7,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.1,
                "lineHeight": 8,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 4
                },
                "optional": true,
                "shadow": {
                  "color": "#80000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "misans",
                "size": 6.5,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.02,
                "lineHeight": 7,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 3
                },
                "optional": true,
                "shadow": {
                  "color": "#80000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "leica_live_moment_style_1-leica_live_moment_logo": {
        "file": "oppo/camerapro/leica_live_moment_logo.webp",
        "width": 18,
        "height": 18
      },
      "leica_live_moment_style_1-leica_live_moment_corner_left": {
        "file": "oppo/camerapro/leica_live_moment_corner_left.webp",
        "width": 4,
        "height": 10
      },
      "leica_live_moment_style_1-leica_live_moment_corner_right": {
        "file": "oppo/camerapro/leica_live_moment_corner_right.webp",
        "width": 4,
        "height": 10
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  },
  {
    "id": "oppo-camerapro:liquid_glass_style_1",
    "vendor": "oppo",
    "family": "OPPO 相机",
    "name": "液态玻璃",
    "nameEn": "LIQUID GLASS",
    "placement": "overlay",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "background": "#00000000",
    "bands": [
      {
        "direction": "overlay",
        "thickness": 46,
        "width": 326,
        "gravity": "centerBottom",
        "offset": {
          "x": 0,
          "y": 16
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 120,
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 17
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 9.5,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 0.96,
                "tracking": 0.01,
                "lineHeight": 12,
                "align": "left",
                "gravity": "leftVerticalCenter",
                "margins": {},
                "optional": true,
                "shadow": {
                  "color": "#59000000",
                  "blur": 3,
                  "x": 1,
                  "y": 0
                }
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
            "width": 170,
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 17
            },
            "children": [
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "rightTop",
                "margins": {},
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "imageParams"
                    },
                    "font": "sys",
                    "size": 6.8,
                    "weight": 500,
                    "color": "#FFFFFF",
                    "alpha": 0.94,
                    "tracking": 0.01,
                    "lineHeight": 8.5,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": true,
                    "shadow": {
                      "color": "#59000000",
                      "blur": 3,
                      "x": 1,
                      "y": 0
                    }
                  }
                ]
              },
              {
                "kind": "text",
                "token": {
                  "kind": "time"
                },
                "font": "sys",
                "size": 6.5,
                "weight": 400,
                "color": "#FFFFFF",
                "alpha": 0.9,
                "tracking": 0.02,
                "lineHeight": 8,
                "align": "left",
                "gravity": "rightTop",
                "margins": {
                  "top": 2
                },
                "optional": true,
                "shadow": {
                  "color": "#59000000",
                  "blur": 3,
                  "x": 1,
                  "y": 0
                }
              }
            ]
          }
        ],
        "background": "rgba(255,255,255,0.168)",
        "radius": 23
      }
    ],
    "assets": {},
    "audit": {
      "status": "approx",
      "unsupported": [
        "liquid glass blur/refraction approximated by translucent rounded fill"
      ],
      "notes": "Auto-converted from OPPO CameraPro config; pending visual audit."
    }
  }
] as StockStyle[]
