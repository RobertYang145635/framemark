import type { StockStyle } from '../ir'

export const OPPO_GALLERY_STYLES = [
  {
    "id": "oppo-gallery:hassel_style_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏 · 1",
    "nameEn": "hassel_style_1",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 60
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 60,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 160,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 13,
                "weight": 700,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0.05,
                "lineHeight": 15.24,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "left": 24
                },
                "optional": false
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
            "width": 148,
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 24
            },
            "children": [
              {
                "kind": "image",
                "asset": "hasselblad_watermark_dark",
                "width": 112.8,
                "height": 8.96,
                "scale": "fill",
                "gravity": "rightTop",
                "margins": {},
                "alpha": 1
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "rightBottom",
                "margins": {
                  "top": 8.94
                },
                "children": [
                  {
                    "kind": "shape",
                    "shape": "circle",
                    "width": 7.2,
                    "height": 7.2,
                    "color": "#FF8933",
                    "gravity": "leftVerticalCenter",
                    "margins": {}
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "imageParams"
                    },
                    "font": "sys",
                    "size": 7.2,
                    "weight": 700,
                    "color": "#000000",
                    "alpha": 0.7,
                    "tracking": 0.03,
                    "lineHeight": 8.44,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6.6
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
    "assets": {
      "hasselblad_watermark_dark": {
        "file": "hasselblad_watermark_dark.webp",
        "width": 1475,
        "height": 118
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:hassel_style_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏 · 2",
    "nameEn": "hassel_style_2",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 68
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 68,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 230,
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 19
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "avenir",
                "size": 11,
                "weight": 500,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 15.03,
                "align": "left",
                "gravity": "leftTop",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "avenir",
                "size": 11,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.65,
                "tracking": 0,
                "lineHeight": 15.03,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "top": 1
                },
                "optional": false
              }
            ]
          },
          {
            "kind": "space",
            "weight": 1
          },
          {
            "kind": "image",
            "asset": "hassel_watermark_h_logo_dark",
            "width": 36,
            "height": 36,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 19
            },
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "hassel_watermark_h_logo_dark": {
        "file": "hassel_watermark_h_logo_dark.webp",
        "width": 512,
        "height": 512
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:hassel_style_3",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏 · 3",
    "nameEn": "hassel_style_3",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 10,
      "bottom": 111
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 111,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
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
                "asset": "hassel_watermark_h_logo_dark",
                "width": 36,
                "height": 36,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {},
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "avenir",
                "size": 11.6,
                "weight": 500,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 15.85,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 4
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "avenir",
                "size": 10,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.65,
                "tracking": 0,
                "lineHeight": 13.66,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 1
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "hassel_watermark_h_logo_dark": {
        "file": "hassel_watermark_h_logo_dark.webp",
        "width": 512,
        "height": 512
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:hassel_style_4",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏 · 4",
    "nameEn": "hassel_style_4",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 79,
      "right": 10,
      "bottom": 79
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 79
        },
        "gravity": "center",
        "children": [
          {
            "kind": "image",
            "asset": "hasselblad_watermark_dark",
            "width": 116,
            "height": 9,
            "scale": "contain",
            "gravity": "center",
            "margins": {},
            "alpha": 1
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
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
                "font": "avenir",
                "size": 12,
                "weight": 500,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0.03,
                "lineHeight": 16.39,
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
                  "top": 3
                },
                "children": [
                  {
                    "kind": "group",
                    "orientation": "row",
                    "gravity": "horCenter",
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}"
                        },
                        "font": "avenir",
                        "size": 8.45,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 1,
                        "tracking": 0.03,
                        "lineHeight": 11.54,
                        "align": "left",
                        "gravity": "verticalCenter",
                        "margins": {
                          "left": 8
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "mm"
                        },
                        "font": "avenir",
                        "size": 8.45,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 1,
                        "tracking": 0.03,
                        "lineHeight": 11.54,
                        "align": "left",
                        "gravity": "verticalCenter",
                        "margins": {},
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "f/"
                        },
                        "font": "avenir",
                        "size": 8.45,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.65,
                        "tracking": 0.03,
                        "lineHeight": 11.54,
                        "align": "left",
                        "gravity": "verticalCenter",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{aperture}"
                        },
                        "font": "avenir",
                        "size": 8.45,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 1,
                        "tracking": 0.03,
                        "lineHeight": 11.54,
                        "align": "left",
                        "gravity": "verticalCenter",
                        "margins": {
                          "left": 8
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}"
                        },
                        "font": "avenir",
                        "size": 8.45,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 1,
                        "tracking": 0.03,
                        "lineHeight": 11.54,
                        "align": "left",
                        "gravity": "verticalCenter",
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
                        "font": "avenir",
                        "size": 8.45,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.65,
                        "tracking": 0.03,
                        "lineHeight": 11.54,
                        "align": "left",
                        "gravity": "verticalCenter",
                        "margins": {
                          "left": 16
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{iso}"
                        },
                        "font": "avenir",
                        "size": 8.45,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 1,
                        "tracking": 0.03,
                        "lineHeight": 11.54,
                        "align": "left",
                        "gravity": "verticalCenter",
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
      }
    ],
    "assets": {
      "hasselblad_watermark_dark": {
        "file": "hasselblad_watermark_dark.webp",
        "width": 1475,
        "height": 118
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:hassel_style_master_mode_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏 · 大师模式",
    "nameEn": "hassel_style_master_mode_1",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 79,
      "right": 10,
      "bottom": 79
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 79
        },
        "gravity": "center",
        "children": [
          {
            "kind": "image",
            "asset": "hasselblad_watermark_dark",
            "width": 116,
            "height": 9.28,
            "scale": "contain",
            "gravity": "center",
            "margins": {},
            "alpha": 1
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "center",
            "margins": {
              "left": 39,
              "right": 39
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "avenir",
                "size": 11,
                "weight": 600,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 15,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {},
                "optional": false
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "bottomHorCenter",
                "margins": {
                  "top": 5,
                  "bottom": 2.5
                },
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_filter_intensity_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "avenir",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 5
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_soft_light_dark",
                    "width": 10,
                    "height": 10,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "avenir",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 1.2
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_tone_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "avenir",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_filter_saturation_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "avenir",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_color_temperature_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "avenir",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_cyan_magenta_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "avenir",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_sharpness_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "avenir",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_vig_enable_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "avenir",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
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
    "assets": {
      "hasselblad_watermark_dark": {
        "file": "hasselblad_watermark_dark.webp",
        "width": 1475,
        "height": 118
      },
      "master_model_filter_intensity_dark": {
        "file": "master_model_filter_intensity_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_soft_light_dark": {
        "file": "master_model_soft_light_dark.webp",
        "width": 84,
        "height": 84
      },
      "master_model_tone_dark": {
        "file": "master_model_tone_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_filter_saturation_dark": {
        "file": "master_model_filter_saturation_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_color_temperature_dark": {
        "file": "master_model_color_temperature_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_cyan_magenta_dark": {
        "file": "master_model_cyan_magenta_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_sharpness_dark": {
        "file": "master_model_sharpness_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_vig_enable_dark": {
        "file": "master_model_vig_enable_dark.webp",
        "width": 60,
        "height": 60
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:hassel_style_xpan_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏 · XPAN",
    "nameEn": "hassel_style_xpan_1",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 15,
      "top": 95,
      "right": 15,
      "bottom": 95
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 95,
        "offset": {
          "x": 0,
          "y": 95
        },
        "gravity": "leftVerticalCenter",
        "children": [
          {
            "kind": "image",
            "asset": "xpan_watermark_dark",
            "width": 86,
            "height": 35,
            "scale": "contain",
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 25.5
            },
            "alpha": 1
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 95,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "image",
            "asset": "hasselblad_watermark_dark",
            "width": 117.15,
            "height": 9.31,
            "scale": "fill",
            "gravity": "rightVerticalCenter",
            "margins": {
              "top": 4.58,
              "right": 26
            },
            "alpha": 1
          },
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 24
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "avenir",
                "size": 13.86,
                "weight": 500,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 16.82,
                "align": "left",
                "gravity": "leftTop",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "avenir",
                "size": 9.9,
                "weight": 400,
                "color": "#99000000",
                "alpha": 1,
                "tracking": 0,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "top": -3
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "xpan_watermark_dark": {
        "file": "xpan_watermark_dark.webp",
        "width": 345,
        "height": 140
      },
      "hasselblad_watermark_dark": {
        "file": "hasselblad_watermark_dark.webp",
        "width": 1475,
        "height": 118
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:hassel_text_style_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏文字 · 1",
    "nameEn": "hassel_text_style_1",
    "placement": "in",
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
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "center",
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
                "asset": "hassel_watermark_h_logo_text_style",
                "width": 24,
                "height": 16.82,
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
                "font": "avenir",
                "size": 10,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.03,
                "lineHeight": 13.66,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 6.18
                },
                "optional": false,
                "shadow": {
                  "color": "#65000000",
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
      "hassel_watermark_h_logo_text_style": {
        "file": "hassel_watermark_h_logo_text_style.png",
        "width": 526,
        "height": 397
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:hassel_text_style_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏文字 · 2",
    "nameEn": "hassel_text_style_2",
    "placement": "in",
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
        "thickness": 36,
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "gravity": "topHorCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "avenir",
                "size": 11,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.03,
                "lineHeight": 15.03,
                "align": "left",
                "gravity": "verticalCenter",
                "margins": {},
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "shape",
                "shape": "rect",
                "width": 1.4,
                "height": 7.5,
                "color": "#FFFFFF",
                "gravity": "verticalCenter",
                "margins": {
                  "left": 6.5,
                  "top": 0.9,
                  "right": 5.5
                }
              },
              {
                "kind": "image",
                "asset": "hasselblad_watermark_text_style",
                "width": 98,
                "height": 11.7,
                "scale": "contain",
                "gravity": "verticalCenter",
                "margins": {
                  "top": 0.9
                },
                "alpha": 1
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "hasselblad_watermark_text_style": {
        "file": "hasselblad_watermark_text_style.webp",
        "width": 518,
        "height": 53
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:lumo_style_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "LUMO · 1",
    "nameEn": "lumo_style_1",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 60
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 60,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "image",
            "asset": "lumo_watermark_logo_dark",
            "width": 52,
            "height": 32,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 16
            },
            "alpha": 1
          },
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
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
                "lineHeight": 14,
                "align": "left",
                "gravity": "leftTop",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 7,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 8,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "top": 1
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "lumo_watermark_logo_dark": {
        "file": "lumo_watermark_logo_dark.webp",
        "width": 416,
        "height": 256
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:lumo_style_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "LUMO · 2",
    "nameEn": "lumo_style_2",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 10,
      "bottom": 111
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 111,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 340,
            "gravity": "center",
            "margins": {
              "bottom": 10.25
            },
            "children": [
              {
                "kind": "image",
                "asset": "lumo_watermark_logo_dark",
                "width": 65,
                "height": 40,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {},
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 10.5,
                "weight": 600,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0.02,
                "lineHeight": 12,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 2.5
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 9,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 2
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "lumo_watermark_logo_dark": {
        "file": "lumo_watermark_logo_dark.webp",
        "width": 416,
        "height": 256
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:lumo_style_3",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "LUMO · 3",
    "nameEn": "lumo_style_3",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 79,
      "right": 10,
      "bottom": 79
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 79
        },
        "gravity": "center",
        "children": [
          {
            "kind": "image",
            "asset": "lumo_watermark_logo_dark",
            "width": 65,
            "height": 40,
            "scale": "contain",
            "gravity": "center",
            "margins": {},
            "alpha": 1
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
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
                "lineHeight": 14,
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
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 9,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {
                  "top": 3
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "lumo_watermark_logo_dark": {
        "file": "lumo_watermark_logo_dark.webp",
        "width": 416,
        "height": 256
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:lumo_style_master_mode_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "LUMO · 大师模式",
    "nameEn": "lumo_style_master_mode_1",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 79,
      "right": 10,
      "bottom": 79
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 79
        },
        "gravity": "center",
        "children": [
          {
            "kind": "image",
            "asset": "lumo_watermark_logo_dark",
            "width": 65,
            "height": 40,
            "scale": "contain",
            "gravity": "center",
            "margins": {},
            "alpha": 1
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "center",
            "margins": {
              "left": 39,
              "right": 39
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 11,
                "weight": 600,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 15,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {},
                "optional": false
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "bottomHorCenter",
                "margins": {
                  "top": 5,
                  "bottom": 2.5
                },
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_filter_intensity_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 5
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_soft_light_dark",
                    "width": 10,
                    "height": 10,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 1.2
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_tone_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_filter_saturation_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_color_temperature_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_cyan_magenta_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_sharpness_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
                    },
                    "optional": false
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.52,
                    "height": 5.3,
                    "color": "#1F000000",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 6,
                      "right": 6
                    }
                  },
                  {
                    "kind": "image",
                    "asset": "master_model_vig_enable_dark",
                    "width": 7.5,
                    "height": 7.5,
                    "scale": "contain",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "alpha": 1
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "custom"
                    },
                    "font": "sys",
                    "size": 7,
                    "weight": 500,
                    "color": "#000000",
                    "alpha": 0.6,
                    "tracking": 0,
                    "lineHeight": 8,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "left": 2.8
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
    "assets": {
      "lumo_watermark_logo_dark": {
        "file": "lumo_watermark_logo_dark.webp",
        "width": 416,
        "height": 256
      },
      "master_model_filter_intensity_dark": {
        "file": "master_model_filter_intensity_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_soft_light_dark": {
        "file": "master_model_soft_light_dark.webp",
        "width": 84,
        "height": 84
      },
      "master_model_tone_dark": {
        "file": "master_model_tone_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_filter_saturation_dark": {
        "file": "master_model_filter_saturation_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_color_temperature_dark": {
        "file": "master_model_color_temperature_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_cyan_magenta_dark": {
        "file": "master_model_cyan_magenta_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_sharpness_dark": {
        "file": "master_model_sharpness_dark.webp",
        "width": 60,
        "height": 60
      },
      "master_model_vig_enable_dark": {
        "file": "master_model_vig_enable_dark.webp",
        "width": 60,
        "height": 60
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:lumo_text_style_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "LUMO 文字 · 1",
    "nameEn": "lumo_text_style_1",
    "placement": "in",
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
        "thickness": 56,
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "center",
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
                "asset": "lumo_text_watermark_logo",
                "width": 54,
                "height": 15.27,
                "scale": "contain",
                "gravity": "topHorCenter",
                "margins": {
                  "top": 8
                },
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 10,
                "weight": 500,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.03,
                "lineHeight": 12,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 5
                },
                "optional": false,
                "shadow": {
                  "color": "#65000000",
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
      "lumo_text_watermark_logo": {
        "file": "lumo_text_watermark_logo.webp",
        "width": 428,
        "height": 121
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:lumo_text_style_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "LUMO 文字 · 2",
    "nameEn": "lumo_text_style_2",
    "placement": "in",
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
        "thickness": 36,
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "gravity": "topHorCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 11,
                "weight": 500,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.03,
                "lineHeight": 13,
                "align": "left",
                "gravity": "verticalCenter",
                "margins": {},
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "shape",
                "shape": "rect",
                "width": 1.4,
                "height": 7.5,
                "color": "#FFFFFF",
                "gravity": "verticalCenter",
                "margins": {
                  "left": 8,
                  "top": 1.8,
                  "right": 8
                }
              },
              {
                "kind": "image",
                "asset": "lumo_text_watermark_logo",
                "width": 33,
                "height": 9.33,
                "scale": "contain",
                "gravity": "verticalCenter",
                "margins": {
                  "top": 1.8
                },
                "alpha": 1
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "lumo_text_watermark_logo": {
        "file": "lumo_text_watermark_logo.webp",
        "width": 428,
        "height": 121
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_brand_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "品牌 · 1",
    "nameEn": "personalize_brand_1",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 79,
      "right": 10,
      "bottom": 79
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 79
        },
        "gravity": "center",
        "children": [
          {
            "kind": "image",
            "asset": "brand_color_os_dark",
            "width": 156,
            "height": 40,
            "scale": "contain",
            "gravity": "center",
            "margins": {},
            "alpha": 1
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
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
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 2
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "brand_color_os_dark": {
        "file": "brand_color_os_dark.webp",
        "width": 529,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_brand_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "品牌 · 2",
    "nameEn": "personalize_brand_2",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 162,
      "bottom": 10
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "right",
        "thickness": 162,
        "offset": {
          "x": 10,
          "y": 10
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 122,
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "brand_find_x_dark",
                "width": 68,
                "height": 40,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {},
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 12,
                "weight": 600,
                "color": "#000000",
                "alpha": 0.9,
                "tracking": 0.02,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 14
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 8
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "time"
                },
                "font": "sys",
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 5
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "brand_find_x_dark": {
        "file": "brand_find_x_dark.webp",
        "width": 320,
        "height": 320
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_brand_3",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "品牌 · 3",
    "nameEn": "personalize_brand_3",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 50,
      "bottom": 0
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "right",
        "thickness": 50,
        "offset": {
          "x": 0,
          "y": 0
        },
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
            "alpha": 0.54,
            "tracking": 0.04,
            "lineHeight": 9.38,
            "align": "left",
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
            "optional": false
          },
          {
            "kind": "text",
            "token": {
              "kind": "timeLocation"
            },
            "font": "sys",
            "size": 8,
            "weight": 500,
            "color": "#000000",
            "alpha": 0.54,
            "tracking": 0.03,
            "lineHeight": 9.38,
            "align": "left",
            "gravity": "verticalCenter",
            "margins": {
              "left": 12
            },
            "optional": false
          },
          {
            "kind": "image",
            "asset": "brand_imagine_dark",
            "width": 97,
            "height": 34,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 20
            },
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "brand_imagine_dark": {
        "file": "brand_imagine_dark.webp",
        "width": 528,
        "height": 272
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_brand_5",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "品牌 · 5",
    "nameEn": "personalize_brand_5",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 10,
      "bottom": 111
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 111,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 340,
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "brand_break_through_dark",
                "width": 68,
                "height": 40,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {
                  "bottom": 6.5
                },
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 12,
                "weight": 600,
                "color": "#000000",
                "alpha": 0.9,
                "tracking": 0.02,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "bottom": 3
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "horCenter",
                "margins": {},
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "brand_break_through_dark": {
        "file": "brand_break_through_dark.webp",
        "width": 320,
        "height": 320
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_brand_6",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "品牌 · 6",
    "nameEn": "personalize_brand_6",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 60
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 60,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 230,
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
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
                "alpha": 0.9,
                "tracking": 0.04,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "leftTop",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 7,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 8.2,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "top": 2.81
                },
                "optional": false
              }
            ]
          },
          {
            "kind": "space",
            "weight": 1
          },
          {
            "kind": "image",
            "asset": "brand_lumo_image_dark",
            "width": 74,
            "height": 32,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 16
            },
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "brand_lumo_image_dark": {
        "file": "brand_lumo_image_dark.webp",
        "width": 360,
        "height": 256
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_film_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "胶片 · 1",
    "nameEn": "personalize_film_1",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 60
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 60,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 230,
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
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
                "alpha": 0.9,
                "tracking": 0.04,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "leftTop",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 7,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 8.2,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "top": 2.81
                },
                "optional": false
              }
            ]
          },
          {
            "kind": "space",
            "weight": 1
          },
          {
            "kind": "image",
            "asset": "film_street_sweeping_dark",
            "width": 74,
            "height": 32,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 16
            },
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "film_street_sweeping_dark": {
        "file": "film_street_sweeping_dark.webp",
        "width": 368,
        "height": 256
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_film_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "胶片 · 2",
    "nameEn": "personalize_film_2",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 50,
      "bottom": 0
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "right",
        "thickness": 50,
        "offset": {
          "x": 0,
          "y": 0
        },
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
            "alpha": 0.54,
            "tracking": 0.04,
            "lineHeight": 9.38,
            "align": "left",
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
            "optional": false
          },
          {
            "kind": "text",
            "token": {
              "kind": "timeLocation"
            },
            "font": "sys",
            "size": 8,
            "weight": 500,
            "color": "#000000",
            "alpha": 0.54,
            "tracking": 0.03,
            "lineHeight": 9.38,
            "align": "left",
            "gravity": "verticalCenter",
            "margins": {
              "left": 12
            },
            "optional": false
          },
          {
            "kind": "image",
            "asset": "film_daylight_vertical_dark",
            "width": 97,
            "height": 34,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 20
            },
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "film_daylight_vertical_dark": {
        "file": "film_daylight_vertical_dark.webp",
        "width": 400,
        "height": 272
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_masterSign_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "大师签名 · 1",
    "nameEn": "personalize_masterSign_1",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 79,
      "right": 10,
      "bottom": 79
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "top",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 79
        },
        "gravity": "center",
        "children": [
          {
            "kind": "image",
            "asset": "master_sign_1_dark",
            "width": 156,
            "height": 40,
            "scale": "contain",
            "gravity": "center",
            "margins": {},
            "alpha": 1
          }
        ]
      },
      {
        "direction": "bottom",
        "thickness": 79,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 340,
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
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 2
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "master_sign_1_dark": {
        "file": "master_sign_1_dark.webp",
        "width": 1001,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_masterSign_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "大师签名 · 2",
    "nameEn": "personalize_masterSign_2",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 10,
      "bottom": 136
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 136,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "center",
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "master_sign_2_dark",
                "width": 156,
                "height": 32,
                "scale": "contain",
                "gravity": "topHorCenter",
                "margins": {
                  "bottom": 22
                },
                "alpha": 1
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "horCenter",
                "margins": {},
                "children": [
                  {
                    "kind": "group",
                    "orientation": "column",
                    "width": 90,
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{iso}"
                        },
                        "font": "sys",
                        "size": 12,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.9,
                        "tracking": 0,
                        "lineHeight": 15.26,
                        "align": "left",
                        "gravity": "horCenter",
                        "margins": {
                          "top": 1.26
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "ISO"
                        },
                        "font": "sys",
                        "size": 9,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.54,
                        "tracking": 0,
                        "lineHeight": 13.35,
                        "align": "left",
                        "gravity": "horCenter",
                        "margins": {
                          "top": 1.26
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.5,
                    "height": 13,
                    "color": "#4c000000",
                    "gravity": "verticalCenter",
                    "margins": {}
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "width": 90,
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{aperture}"
                        },
                        "font": "sys",
                        "size": 12,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.9,
                        "tracking": 0,
                        "lineHeight": 15.26,
                        "align": "left",
                        "gravity": "horCenter",
                        "margins": {
                          "top": 1.26
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "F"
                        },
                        "font": "sys",
                        "size": 9,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.54,
                        "tracking": 0,
                        "lineHeight": 13.35,
                        "align": "left",
                        "gravity": "horCenter",
                        "margins": {
                          "top": 1.26
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.5,
                    "height": 13,
                    "color": "#4c000000",
                    "gravity": "verticalCenter",
                    "margins": {}
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "width": 90,
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{focal}"
                        },
                        "font": "sys",
                        "size": 12,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.9,
                        "tracking": 0,
                        "lineHeight": 15.26,
                        "align": "left",
                        "gravity": "horCenter",
                        "margins": {
                          "top": 1.26
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "mm"
                        },
                        "font": "sys",
                        "size": 9,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.54,
                        "tracking": 0,
                        "lineHeight": 13.35,
                        "align": "left",
                        "gravity": "horCenter",
                        "margins": {
                          "top": 1.26
                        },
                        "optional": false
                      }
                    ]
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.5,
                    "height": 13,
                    "color": "#4c000000",
                    "gravity": "verticalCenter",
                    "margins": {}
                  },
                  {
                    "kind": "group",
                    "orientation": "column",
                    "width": 90,
                    "margins": {},
                    "children": [
                      {
                        "kind": "text",
                        "token": {
                          "kind": "template",
                          "value": "@exif=@{speed}"
                        },
                        "font": "sys",
                        "size": 12,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.9,
                        "tracking": 0,
                        "lineHeight": 15.26,
                        "align": "left",
                        "gravity": "horCenter",
                        "margins": {
                          "top": 1.26
                        },
                        "optional": false
                      },
                      {
                        "kind": "text",
                        "token": {
                          "kind": "literal",
                          "value": "s"
                        },
                        "font": "sys",
                        "size": 9,
                        "weight": 500,
                        "color": "#000000",
                        "alpha": 0.54,
                        "tracking": 0,
                        "lineHeight": 13.35,
                        "align": "left",
                        "gravity": "horCenter",
                        "margins": {
                          "top": 1.26
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
    "assets": {
      "master_sign_2_dark": {
        "file": "master_sign_2_dark.webp",
        "width": 888,
        "height": 272
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_masterSign_3",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "大师签名 · 3",
    "nameEn": "personalize_masterSign_3",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 50,
      "bottom": 0
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "right",
        "thickness": 50,
        "offset": {
          "x": 0,
          "y": 0
        },
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
            "alpha": 0.54,
            "tracking": 0.04,
            "lineHeight": 9.38,
            "align": "left",
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
            "optional": false
          },
          {
            "kind": "text",
            "token": {
              "kind": "timeLocation"
            },
            "font": "sys",
            "size": 8,
            "weight": 500,
            "color": "#000000",
            "alpha": 0.54,
            "tracking": 0.03,
            "lineHeight": 9.38,
            "align": "left",
            "gravity": "verticalCenter",
            "margins": {
              "left": 12
            },
            "optional": false
          },
          {
            "kind": "image",
            "asset": "master_sign_3_dark",
            "width": 97.31,
            "height": 31.12,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 20
            },
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "master_sign_3_dark": {
        "file": "master_sign_3_dark.webp",
        "width": 750,
        "height": 204
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_masterSign_4",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "大师签名 · 4",
    "nameEn": "personalize_masterSign_4",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 60
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 60,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 230,
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
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
                "alpha": 0.9,
                "tracking": 0.04,
                "lineHeight": 14.06,
                "align": "left",
                "gravity": "leftTop",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "sys",
                "size": 7,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 8.2,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "top": 2.81
                },
                "optional": false
              }
            ]
          },
          {
            "kind": "space",
            "weight": 1
          },
          {
            "kind": "image",
            "asset": "master_sign_4_dark",
            "width": 74,
            "height": 32,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 16,
              "bottom": 5
            },
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "master_sign_4_dark": {
        "file": "master_sign_4_dark.webp",
        "width": 686,
        "height": 204
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_retroCamera_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "复古相机 · 1",
    "nameEn": "personalize_retroCamera_1",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 10,
      "bottom": 111
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 111,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 340,
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "classic_double_reverse1_dark",
                "width": 156,
                "height": 40,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {
                  "bottom": 6.5
                },
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "butler",
                "size": 10.5,
                "weight": 700,
                "color": "#000000",
                "alpha": 0.9,
                "tracking": 0.04,
                "lineHeight": 12.6,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "bottom": 3
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "fz",
                "size": 7,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 8.6,
                "align": "left",
                "gravity": "horCenter",
                "margins": {},
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "classic_double_reverse1_dark": {
        "file": "classic_double_reverse1_dark.webp",
        "width": 320,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_retroCamera_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "复古相机 · 2",
    "nameEn": "personalize_retroCamera_2",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 10,
      "bottom": 95
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 95,
        "offset": {
          "x": 10,
          "y": 0
        },
        "gravity": "center",
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
                "width": 239,
                "gravity": "leftVerticalCenter",
                "margins": {
                  "left": 10
                },
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "model"
                    },
                    "font": "butler",
                    "size": 12,
                    "weight": 700,
                    "color": "#000000",
                    "alpha": 1,
                    "tracking": 0.04,
                    "lineHeight": 14.4,
                    "align": "left",
                    "gravity": "leftTop",
                    "margins": {},
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "imageParams"
                    },
                    "font": "fz",
                    "size": 7,
                    "weight": 400,
                    "color": "#000000",
                    "alpha": 0.54,
                    "tracking": 0.06,
                    "lineHeight": 8.6,
                    "align": "left",
                    "gravity": "leftTop",
                    "margins": {
                      "top": 4
                    },
                    "optional": false
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "time"
                    },
                    "font": "fz",
                    "size": 7,
                    "weight": 400,
                    "color": "#000000",
                    "alpha": 0.54,
                    "tracking": 0.06,
                    "lineHeight": 8.6,
                    "align": "left",
                    "gravity": "leftTop",
                    "margins": {
                      "top": 4
                    },
                    "optional": false
                  }
                ]
              },
              {
                "kind": "space",
                "weight": 1
              },
              {
                "kind": "image",
                "asset": "classic_135_camera_dark",
                "width": 85,
                "height": 40,
                "scale": "contain",
                "gravity": "rightVerticalCenter",
                "margins": {
                  "right": 6
                },
                "alpha": 1
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "classic_135_camera_dark": {
        "file": "classic_135_camera_dark.webp",
        "width": 320,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_retroCamera_3",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "复古相机 · 3",
    "nameEn": "personalize_retroCamera_3",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 60
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 60,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 230,
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "butler",
                "size": 12,
                "weight": 700,
                "color": "#000000",
                "alpha": 0.9,
                "tracking": 0.04,
                "lineHeight": 14.4,
                "align": "left",
                "gravity": "leftTop",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "fz",
                "size": 7,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 8.6,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "top": 2.81
                },
                "optional": false
              }
            ]
          },
          {
            "kind": "space",
            "weight": 1
          },
          {
            "kind": "image",
            "asset": "classic_double_reverse4_dark",
            "width": 74,
            "height": 32,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {
              "right": 16
            },
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "classic_double_reverse4_dark": {
        "file": "classic_double_reverse4_dark.webp",
        "width": 320,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_retroCamera_4",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "复古相机 · 4",
    "nameEn": "personalize_retroCamera_4",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 220,
      "bottom": 10
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "right",
        "thickness": 220,
        "offset": {
          "x": 10,
          "y": 10
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 180,
            "margins": {},
            "children": [
              {
                "kind": "image",
                "asset": "classic_double_reverse3_dark",
                "width": 68,
                "height": 40,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {},
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "butler",
                "size": 12,
                "weight": 700,
                "color": "#000000",
                "alpha": 1,
                "tracking": 0.02,
                "lineHeight": 14.4,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 14,
                  "bottom": 8
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "imageParams"
                },
                "font": "fz",
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 9.83,
                "align": "left",
                "gravity": "horCenter",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "time"
                },
                "font": "fz",
                "size": 8,
                "weight": 400,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.06,
                "lineHeight": 9.83,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 5
                },
                "optional": false
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "classic_double_reverse3_dark": {
        "file": "classic_double_reverse3_dark.webp",
        "width": 320,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:personalize_retroCamera_5",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "复古相机 · 5",
    "nameEn": "personalize_retroCamera_5",
    "placement": "with",
    "referenceWidth": 360,
    "margins": {
      "left": 10,
      "top": 10,
      "right": 162,
      "bottom": 10
    },
    "background": "#FFFFFF",
    "bands": [
      {
        "direction": "right",
        "thickness": 162,
        "offset": {
          "x": 10,
          "y": 10
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{iso}"
                },
                "font": "butler",
                "size": 12,
                "weight": 500,
                "color": "#000000",
                "alpha": 0.9,
                "tracking": 0.05,
                "lineHeight": 14.4,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "literal",
                  "value": "ISO"
                },
                "font": "butler",
                "size": 8,
                "weight": 500,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 9.6,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 2
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{aperture}"
                },
                "font": "butler",
                "size": 12,
                "weight": 500,
                "color": "#000000",
                "alpha": 0.9,
                "tracking": 0.05,
                "lineHeight": 14.4,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {
                  "top": 28
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "literal",
                  "value": "F"
                },
                "font": "butler",
                "size": 8,
                "weight": 500,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 9.6,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 2
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{focal}"
                },
                "font": "butler",
                "size": 12,
                "weight": 500,
                "color": "#000000",
                "alpha": 0.9,
                "tracking": 0.05,
                "lineHeight": 14.4,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {
                  "top": 28
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "literal",
                  "value": "mm"
                },
                "font": "butler",
                "size": 8,
                "weight": 500,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 9.6,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 2
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "template",
                  "value": "@exif=@{speed}"
                },
                "font": "butler",
                "size": 12,
                "weight": 500,
                "color": "#000000",
                "alpha": 0.9,
                "tracking": 0.05,
                "lineHeight": 14.4,
                "align": "left",
                "gravity": "topHorCenter",
                "margins": {
                  "top": 28
                },
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "literal",
                  "value": "s"
                },
                "font": "butler",
                "size": 8,
                "weight": 500,
                "color": "#000000",
                "alpha": 0.54,
                "tracking": 0.08,
                "lineHeight": 9.6,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 2
                },
                "optional": false
              },
              {
                "kind": "shape",
                "shape": "rect",
                "width": 18.15,
                "height": 0.7,
                "color": "#4c000000",
                "gravity": "horCenter",
                "margins": {
                  "top": 32
                }
              },
              {
                "kind": "image",
                "asset": "classic_double_reverse2_dark",
                "width": 68,
                "height": 40,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {
                  "top": 30
                },
                "alpha": 1
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "classic_double_reverse2_dark": {
        "file": "classic_double_reverse2_dark.webp",
        "width": 320,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:restrict_local_songkran_holiday_2025_style_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "宋干节 · 1",
    "nameEn": "restrict_local_songkran_holiday_2025_style_1",
    "placement": "out",
    "referenceWidth": 360,
    "margins": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 60
    },
    "background": "#D4EFE5",
    "bands": [
      {
        "direction": "bottom",
        "thickness": 60,
        "offset": {
          "x": 0,
          "y": 0
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "leftVerticalCenter",
            "margins": {
              "left": 20
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 13,
                "weight": 700,
                "color": "#3B2C12",
                "alpha": 1,
                "tracking": 0.05,
                "lineHeight": 15.24,
                "align": "left",
                "gravity": "leftTop",
                "margins": {},
                "optional": false
              },
              {
                "kind": "text",
                "token": {
                  "kind": "greeting"
                },
                "font": "sys",
                "size": 10,
                "weight": 400,
                "color": "#3B2C12",
                "alpha": 1,
                "tracking": 0.03,
                "lineHeight": 12.25,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "top": 3
                },
                "optional": false
              }
            ]
          },
          {
            "kind": "space",
            "weight": 1
          },
          {
            "kind": "image",
            "asset": "songkran_watermark_icon",
            "width": 176,
            "height": 60,
            "scale": "contain",
            "gravity": "rightVerticalCenter",
            "margins": {},
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {},
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:text_style_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "文字 · 1",
    "nameEn": "text_style_1",
    "placement": "in",
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
        "thickness": 29,
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "leftBottom",
        "children": [
          {
            "kind": "image",
            "asset": "classic_double_reverse2",
            "width": 28,
            "height": 28,
            "scale": "contain",
            "gravity": "leftVerticalCenter",
            "margins": {},
            "alpha": 1
          },
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "verticalCenter",
            "margins": {
              "left": 6
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 10,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 11.72,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "bottom": 1.8
                },
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "location"
                    },
                    "font": "sys",
                    "size": 8,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "lineHeight": 9.38,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "right": 4.51
                    },
                    "optional": false,
                    "shadow": {
                      "color": "#65000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.5,
                    "height": 7,
                    "color": "#CCFFFFFF",
                    "gravity": "verticalCenter",
                    "margins": {
                      "top": 0.5,
                      "right": 4.49
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "time"
                    },
                    "font": "sys",
                    "size": 8,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "lineHeight": 9.38,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false,
                    "shadow": {
                      "color": "#65000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "classic_double_reverse2": {
        "file": "classic_double_reverse2.webp",
        "width": 320,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:text_style_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "文字 · 2",
    "nameEn": "text_style_2",
    "placement": "in",
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
        "thickness": 60,
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "center",
        "children": [
          {
            "kind": "text",
            "token": {
              "kind": "model"
            },
            "font": "sys",
            "size": 10,
            "weight": 600,
            "color": "#FFFFFF",
            "alpha": 1,
            "tracking": 0.03,
            "lineHeight": 11.72,
            "align": "left",
            "gravity": "horCenter",
            "margins": {
              "bottom": 1.8
            },
            "optional": false,
            "shadow": {
              "color": "#65000000",
              "blur": 3.3000000000000003,
              "x": 1,
              "y": 0
            }
          },
          {
            "kind": "group",
            "orientation": "row",
            "gravity": "horCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "location"
                },
                "font": "sys",
                "size": 8,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "verticalCenter",
                "margins": {},
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "shape",
                "shape": "rect",
                "width": 0.5,
                "height": 7,
                "color": "#E5FFFFFF",
                "gravity": "verticalCenter",
                "margins": {
                  "left": 4.51,
                  "top": 0.5,
                  "right": 4.45
                }
              },
              {
                "kind": "text",
                "token": {
                  "kind": "time"
                },
                "font": "sys",
                "size": 8,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "verticalCenter",
                "margins": {},
                "optional": false,
                "shadow": {
                  "color": "#65000000",
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
    "assets": {},
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:text_style_3",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "文字 · 3",
    "nameEn": "text_style_3",
    "placement": "in",
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
        "thickness": 29,
        "offset": {
          "x": 20,
          "y": 20
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 209,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 10,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 14.79,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "bottom": 1.8
                },
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 205,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "location"
                    },
                    "font": "sys",
                    "size": 8,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "lineHeight": 11.83,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "right": 4.51
                    },
                    "optional": false,
                    "shadow": {
                      "color": "#65000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.5,
                    "height": 7,
                    "color": "#E5FFFFFF",
                    "gravity": "verticalCenter",
                    "margins": {
                      "top": 0.5,
                      "right": 4.45
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "time"
                    },
                    "font": "sys",
                    "size": 8,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "lineHeight": 11.83,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false,
                    "shadow": {
                      "color": "#65000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
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
            "kind": "image",
            "asset": "text_style_sign_1",
            "width": 91,
            "height": 32,
            "scale": "fill",
            "gravity": "rightVerticalCenter",
            "margins": {},
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "text_style_sign_1": {
        "file": "text_style_sign_1.webp",
        "width": 728,
        "height": 256
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:video_hassel_text_style_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏文字 · 视频 · 1",
    "nameEn": "video_hassel_text_style_1",
    "placement": "in",
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
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "center",
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
                "asset": "hassel_watermark_h_logo_text_style",
                "width": 19,
                "height": 13.82,
                "scale": "contain",
                "gravity": "horCenter",
                "margins": {},
                "alpha": 1
              },
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "avenir",
                "size": 10,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.03,
                "lineHeight": 13.66,
                "align": "left",
                "gravity": "horCenter",
                "margins": {
                  "top": 6.18
                },
                "optional": false,
                "shadow": {
                  "color": "#65000000",
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
      "hassel_watermark_h_logo_text_style": {
        "file": "hassel_watermark_h_logo_text_style.png",
        "width": 526,
        "height": 397
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:video_hassel_text_style_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "哈苏文字 · 视频 · 2",
    "nameEn": "video_hassel_text_style_2",
    "placement": "in",
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
        "thickness": 36,
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "center",
        "children": [
          {
            "kind": "group",
            "orientation": "row",
            "gravity": "topHorCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "avenir",
                "size": 11,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0.03,
                "lineHeight": 15.03,
                "align": "left",
                "gravity": "verticalCenter",
                "margins": {},
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "shape",
                "shape": "rect",
                "width": 1.4,
                "height": 7.5,
                "color": "#FFFFFF",
                "gravity": "verticalCenter",
                "margins": {
                  "left": 6.5,
                  "top": 0.9,
                  "right": 5.5
                }
              },
              {
                "kind": "image",
                "asset": "hasselblad_watermark_text_style",
                "width": 98,
                "height": 11.7,
                "scale": "contain",
                "gravity": "verticalCenter",
                "margins": {
                  "top": 0.9
                },
                "alpha": 1
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "hasselblad_watermark_text_style": {
        "file": "hasselblad_watermark_text_style.webp",
        "width": 518,
        "height": 53
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:video_text_style_1",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "文字 · 视频 · 1",
    "nameEn": "video_text_style_1",
    "placement": "in",
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
        "thickness": 29,
        "offset": {
          "x": 20,
          "y": 20
        },
        "children": [
          {
            "kind": "image",
            "asset": "classic_double_reverse2",
            "width": 28,
            "height": 28,
            "scale": "contain",
            "gravity": "leftVerticalCenter",
            "margins": {},
            "alpha": 1
          },
          {
            "kind": "group",
            "orientation": "column",
            "gravity": "verticalCenter",
            "margins": {
              "left": 6
            },
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 10,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 11.72,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "bottom": 1.8
                },
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "group",
                "orientation": "row",
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "location"
                    },
                    "font": "sys",
                    "size": 8,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "lineHeight": 9.38,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "right": 4.51
                    },
                    "optional": false,
                    "shadow": {
                      "color": "#65000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.5,
                    "height": 7,
                    "color": "#CCFFFFFF",
                    "gravity": "verticalCenter",
                    "margins": {
                      "top": 0.5,
                      "right": 4.49
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "time"
                    },
                    "font": "sys",
                    "size": 8,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "lineHeight": 9.38,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false,
                    "shadow": {
                      "color": "#65000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    "assets": {
      "classic_double_reverse2": {
        "file": "classic_double_reverse2.webp",
        "width": 320,
        "height": 321
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:video_text_style_2",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "文字 · 视频 · 2",
    "nameEn": "video_text_style_2",
    "placement": "in",
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
        "thickness": 60,
        "offset": {
          "x": 20,
          "y": 20
        },
        "gravity": "center",
        "children": [
          {
            "kind": "text",
            "token": {
              "kind": "model"
            },
            "font": "sys",
            "size": 10,
            "weight": 600,
            "color": "#FFFFFF",
            "alpha": 1,
            "tracking": 0.03,
            "lineHeight": 11.72,
            "align": "left",
            "gravity": "horCenter",
            "margins": {
              "bottom": 1.8
            },
            "optional": false,
            "shadow": {
              "color": "#65000000",
              "blur": 3.3000000000000003,
              "x": 1,
              "y": 0
            }
          },
          {
            "kind": "group",
            "orientation": "row",
            "gravity": "horCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "location"
                },
                "font": "sys",
                "size": 8,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "verticalCenter",
                "margins": {},
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "shape",
                "shape": "rect",
                "width": 0.5,
                "height": 7,
                "color": "#E5FFFFFF",
                "gravity": "verticalCenter",
                "margins": {
                  "left": 4.51,
                  "top": 0.5,
                  "right": 4.45
                }
              },
              {
                "kind": "text",
                "token": {
                  "kind": "time"
                },
                "font": "sys",
                "size": 8,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 9.38,
                "align": "left",
                "gravity": "verticalCenter",
                "margins": {},
                "optional": false,
                "shadow": {
                  "color": "#65000000",
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
    "assets": {},
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  },
  {
    "id": "oppo-gallery:video_text_style_3",
    "vendor": "oppo",
    "family": "OPPO 相册",
    "name": "文字 · 视频 · 3",
    "nameEn": "video_text_style_3",
    "placement": "in",
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
        "thickness": 29,
        "offset": {
          "x": 20,
          "y": 20
        },
        "children": [
          {
            "kind": "group",
            "orientation": "column",
            "width": 209,
            "gravity": "leftVerticalCenter",
            "margins": {},
            "children": [
              {
                "kind": "text",
                "token": {
                  "kind": "model"
                },
                "font": "sys",
                "size": 10,
                "weight": 600,
                "color": "#FFFFFF",
                "alpha": 1,
                "tracking": 0,
                "lineHeight": 14.79,
                "align": "left",
                "gravity": "leftTop",
                "margins": {
                  "bottom": 1.8
                },
                "optional": false,
                "shadow": {
                  "color": "#65000000",
                  "blur": 3.3000000000000003,
                  "x": 1,
                  "y": 0
                }
              },
              {
                "kind": "group",
                "orientation": "row",
                "width": 205,
                "gravity": "leftTop",
                "margins": {},
                "children": [
                  {
                    "kind": "text",
                    "token": {
                      "kind": "location"
                    },
                    "font": "sys",
                    "size": 8,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "lineHeight": 11.83,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {
                      "right": 4.51
                    },
                    "optional": false,
                    "shadow": {
                      "color": "#65000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
                  },
                  {
                    "kind": "shape",
                    "shape": "rect",
                    "width": 0.5,
                    "height": 7,
                    "color": "#E5FFFFFF",
                    "gravity": "verticalCenter",
                    "margins": {
                      "top": 0.5,
                      "right": 4.45
                    }
                  },
                  {
                    "kind": "text",
                    "token": {
                      "kind": "time"
                    },
                    "font": "sys",
                    "size": 8,
                    "weight": 600,
                    "color": "#FFFFFF",
                    "alpha": 1,
                    "tracking": 0,
                    "lineHeight": 11.83,
                    "align": "left",
                    "gravity": "verticalCenter",
                    "margins": {},
                    "optional": false,
                    "shadow": {
                      "color": "#65000000",
                      "blur": 3.3000000000000003,
                      "x": 1,
                      "y": 0
                    }
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
            "kind": "image",
            "asset": "text_style_sign_1",
            "width": 91,
            "height": 32,
            "scale": "fill",
            "gravity": "rightVerticalCenter",
            "margins": {},
            "alpha": 1
          }
        ]
      }
    ],
    "assets": {
      "text_style_sign_1": {
        "file": "text_style_sign_1.webp",
        "width": 728,
        "height": 256
      }
    },
    "audit": {
      "status": "approx",
      "notes": "Auto-converted from OPPO Gallery JSON; pending visual audit."
    }
  }
] as StockStyle[]
