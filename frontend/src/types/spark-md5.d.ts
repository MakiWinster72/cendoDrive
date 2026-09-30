declare module 'spark-md5' {
  class SparkMD5ArrayBuffer {
    append(data: ArrayBuffer): SparkMD5ArrayBuffer
    end(raw?: boolean): string
    reset(): void
  }

  const SparkMD5: {
    ArrayBuffer: new () => SparkMD5ArrayBuffer
  }

  export default SparkMD5
}
