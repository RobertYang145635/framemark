declare module 'piexifjs' {
  const piexif: {
    ImageIFD: Record<string, number>
    ExifIFD: Record<string, number>
    GPSIFD: Record<string, number>
    load: (jpeg: string) => any
    dump: (exifObj: any) => string
    insert: (exif: string, jpeg: string) => string
  }
  export default piexif
}
