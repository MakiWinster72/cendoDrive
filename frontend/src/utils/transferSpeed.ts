export function createSpeedSampler(now = () => performance.now()) {
  let time = now();
  let bytes: number | undefined;
  let speed = 0;
  return (loaded: number) => {
    if (!Number.isFinite(loaded) || loaded < 0) return speed;
    const current = now();
    if (bytes === undefined || loaded < bytes) {
      bytes = loaded;
      time = current;
      speed = 0;
    } else if (current - time >= 250) {
      speed = ((loaded - bytes) * 1000) / (current - time);
      bytes = loaded;
      time = current;
    }
    return speed;
  };
}
export function formatSpeed(bytesPerSecond = 0) {
  if (!Number.isFinite(bytesPerSecond) || bytesPerSecond <= 0) return "0 B/s";
  if (bytesPerSecond < 1024) return `${Math.round(bytesPerSecond)} B/s`;
  if (bytesPerSecond < 1024 * 1024)
    return `${(bytesPerSecond / 1024).toFixed(1)} KB/s`;
  return `${(bytesPerSecond / 1024 / 1024).toFixed(1)} MB/s`;
}
