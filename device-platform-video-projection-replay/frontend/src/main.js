import * as Cesium from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';

const viewer = new Cesium.Viewer('map', {
  animation: false,
  timeline: false,
  geocoder: false,
  baseLayerPicker: false,
  baseLayer: false,
  terrainProvider: new Cesium.EllipsoidTerrainProvider()
});
const video = document.querySelector('#video');
const segmentButtons = document.querySelector('#segments');
const status = document.querySelector('#status');
const manifestUrl = new URL('/demo/manifest.json', location.href);
let manifest;
let activeIndex = -1;
let activeCues = [];
let activeCueIndex = -1;
let loadVersion = 0;
let footprint;
let aircraft;
let cameraPlaced = false;

function timeMs(text) {
  const match = /^(\d+):(\d\d):(\d\d),(\d{3})$/.exec(text);
  if (!match) throw new Error('SRT 时间格式错误');
  return ((Number(match[1]) * 60 + Number(match[2])) * 60 + Number(match[3])) * 1000 + Number(match[4]);
}

function parseSrt(text) {
  const cues = [];
  for (const block of text.trim().split(/\r?\n\s*\r?\n/)) {
    const lines = block.trim().split(/\r?\n/);
    if (lines.length < 3) continue;
    const times = lines[1].split(' --> ');
    if (times.length !== 2) continue;
    try {
      const start = timeMs(times[0]);
      const end = timeMs(times[1]);
      const frame = JSON.parse(lines.slice(2).join('\n'));
      if (end > start && frame.geometryVersion === 2
        && frame.cornerOrder === 'CAMERA_TL_TR_BR_BL') {
        cues.push({ start, end, frame });
      }
    } catch (error) {
      console.warn('忽略无效 SRT cue', error);
    }
  }
  return cues.sort((a, b) => a.start - b.start);
}

function cueAt(ms) {
  let low = 0;
  let high = activeCues.length - 1;
  let found = -1;
  while (low <= high) {
    const middle = (low + high) >> 1;
    if (activeCues[middle].start <= ms) {
      found = middle;
      low = middle + 1;
    } else {
      high = middle - 1;
    }
  }
  return found >= 0 && ms < activeCues[found].end ? found : -1;
}

function clearProjection() {
  if (footprint) viewer.entities.remove(footprint);
  if (aircraft) viewer.entities.remove(aircraft);
  footprint = undefined;
  aircraft = undefined;
  activeCueIndex = -1;
}

function render() {
  if (activeIndex < 0) return;
  const segment = manifest.segments[activeIndex];
  const lookupTime = Math.round(video.currentTime * 1000) + segment.syncOffsetMs;
  const index = cueAt(lookupTime);
  if (index === activeCueIndex) return;
  clearProjection();
  if (index < 0) {
    status.textContent = `${segment.videoRecordId} · ${(lookupTime / 1000).toFixed(2)}s\n当前时间没有有效投影帧`;
    return;
  }
  activeCueIndex = index;
  const frame = activeCues[index].frame;
  const corners = frame.viewMode === 'PLANAR' ? frame.corners : frame.spatialCorners;
  if (!Array.isArray(corners) || corners.length !== 4) return;
  const points = corners.map(point => Cesium.Cartesian3.fromDegrees(
    Number(point.longitude), Number(point.latitude),
    frame.viewMode === 'PLANAR' ? 0 : Number(point.height)
  ));
  footprint = viewer.entities.add({
    polygon: {
      hierarchy: new Cesium.PolygonHierarchy(points),
      perPositionHeight: true,
      material: new Cesium.ImageMaterialProperty({ image: video, transparent: false }),
      outline: true,
      outlineColor: Cesium.Color.YELLOW
    }
  });
  aircraft = viewer.entities.add({
    position: Cesium.Cartesian3.fromDegrees(
      Number(frame.longitude), Number(frame.latitude), Number(frame.height)
    ),
    point: { pixelSize: 11, color: Cesium.Color.ORANGE }
  });
  if (!cameraPlaced) {
    viewer.camera.flyTo({
      destination: Cesium.Cartesian3.fromDegrees(
        Number(frame.longitude), Number(frame.latitude), Math.max(350, Number(frame.height) * 8)
      )
    });
    cameraPlaced = true;
  }
  status.textContent = `${segment.videoRecordId} · ${(lookupTime / 1000).toFixed(2)}s\n`
    + `OSD UTC ms: ${frame.reportTimeMs}\n模式: ${frame.viewMode}\n`
    + `cue ${index + 1}/${activeCues.length}`;
}

async function loadSegment(index) {
  const version = ++loadVersion;
  const segment = manifest.segments[index];
  activeIndex = index;
  activeCues = [];
  clearProjection();
  video.pause();
  video.removeAttribute('src');
  video.load();
  status.textContent = `加载 ${segment.videoRecordId}…`;
  try {
    const telemetryUrl = new URL(segment.telemetryUrl, manifestUrl);
    const response = await fetch(telemetryUrl);
    if (!response.ok) throw new Error(`SRT HTTP ${response.status}`);
    const cues = parseSrt(await response.text());
    if (version !== loadVersion) return;
    activeCues = cues;
    video.src = new URL(segment.videoUrl, manifestUrl).href;
    video.load();
    status.textContent = `${segment.videoRecordId}: ${cues.length} 条投影帧`;
  } catch (error) {
    if (version !== loadVersion) return;
    video.src = new URL(segment.videoUrl, manifestUrl).href;
    video.load();
    status.textContent = `SRT 加载失败，视频仍可播放：${error.message}`;
  }
}

video.addEventListener('timeupdate', render);
video.addEventListener('seeked', render);
video.addEventListener('loadeddata', render);
video.addEventListener('pause', render);
video.addEventListener('ended', () => {
  if (activeIndex + 1 < manifest.segments.length) loadSegment(activeIndex + 1);
});
if ('requestVideoFrameCallback' in video) {
  const onFrame = () => {
    render();
    video.requestVideoFrameCallback(onFrame);
  };
  video.requestVideoFrameCallback(onFrame);
}

try {
  const response = await fetch(manifestUrl);
  if (!response.ok) throw new Error(`清单 HTTP ${response.status}`);
  manifest = await response.json();
  if (!Array.isArray(manifest.segments) || manifest.segments.length === 0) {
    throw new Error('清单没有视频分片');
  }
  manifest.segments.forEach((segment, index) => {
    const button = document.createElement('button');
    button.textContent = segment.videoRecordId;
    button.onclick = () => loadSegment(index);
    segmentButtons.append(button);
  });
  await loadSegment(0);
} catch (error) {
  status.textContent = error.message;
}
