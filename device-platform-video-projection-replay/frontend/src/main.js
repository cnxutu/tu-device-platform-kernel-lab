import * as Cesium from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';
import aircraftModel from './assets/drone.glb?url';

const viewer = new Cesium.Viewer('map', {
  animation: false,
  timeline: false,
  geocoder: false,
  baseLayerPicker: false,
  baseLayer: false,
  terrainProvider: new Cesium.EllipsoidTerrainProvider()
});
viewer.scene.globe.baseColor = Cesium.Color.fromCssColorString('#183048');
const localTileUrl = import.meta.env.VITE_MAP_TILE_URL;
viewer.imageryLayers.addImageryProvider(new Cesium.UrlTemplateImageryProvider({
  url: `${Cesium.buildModuleUrl('Assets/Textures/NaturalEarthII')}/{z}/{x}/{reverseY}.jpg`,
  tilingScheme: new Cesium.GeographicTilingScheme(),
  maximumLevel: 2
}));
viewer.imageryLayers.addImageryProvider(localTileUrl
  ? new Cesium.UrlTemplateImageryProvider({ url: localTileUrl })
  : new Cesium.OpenStreetMapImageryProvider({ url: 'https://tile.openstreetmap.org/' }));
const video = document.querySelector('#video');
const followDrone = document.querySelector('#follow-drone');
const followLabel = document.querySelector('#follow-label');
const status = document.querySelector('#status');
const telemetry = document.querySelector('#telemetry');
const osdSlider = document.querySelector('#osd-slider');
const videoTime = document.querySelector('#video-time');
const osdTime = document.querySelector('#osd-time');
const calibration = document.querySelector('#calibration');
const alignment = document.querySelector('#alignment');
const alignButton = document.querySelector('#align-now');
const unlinkButton = document.querySelector('#unlink');
const manifestUrl = new URL('demo/manifest.json', document.baseURI);
let manifest;
let activeCues = [];
let activeCueIndex = -1;
let footprint;
let aircraft;
let frameBorder;
let frustumLines = [];
let trail;
let cameraPlaced = false;
let osdCursorMs = 0;
let offsetMs = 0;
let linked = false;
const calibrationKey = 'projection-replay:drone-video-all:offset-ms';
function aircraftPulse() {
  // Use wall time so the highlight keeps breathing when the video is paused.
  return (1 - Math.cos(performance.now() * Cesium.Math.TWO_PI / 2400)) / 2;
}

const aircraftShader = new Cesium.CustomShader({
  lightingModel: Cesium.LightingModel.UNLIT,
  uniforms: {
    u_pulse: { type: Cesium.UniformType.FLOAT, value: 0 }
  },
  fragmentShaderText: `
    void fragmentMain(FragmentInput fsInput, inout czm_modelMaterial material) {
      float highlight = smoothstep(0.35, 0.7, u_pulse);
      material.diffuse = mix(material.diffuse, vec3(1.0, 0.875, 0.0), highlight);
    }
  `
});
viewer.scene.preRender.addEventListener(() => {
  if (aircraft?.show) aircraftShader.setUniform('u_pulse', aircraftPulse());
});

function formatTime(ms) {
  const sign = ms < 0 ? '−' : '';
  const value = Math.abs(Math.round(ms));
  const minutes = Math.floor(value / 60000);
  const seconds = Math.floor(value / 1000) % 60;
  return `${sign}${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}.${String(value % 1000).padStart(3, '0')}`;
}

function currentVideoMs() {
  return Math.round(video.currentTime * 1000);
}

function effectiveOsdMs() {
  return linked ? currentVideoMs() + offsetMs : osdCursorMs;
}

function updateTimeline() {
  const osdMs = effectiveOsdMs();
  videoTime.textContent = `${formatTime(currentVideoMs())} / ${formatTime(Number.isFinite(video.duration) ? video.duration * 1000 : 0)}`;
  osdTime.textContent = `${formatTime(osdMs)} / ${formatTime(Number(osdSlider.max))}`;
  osdSlider.value = String(Math.max(0, Math.min(Number(osdSlider.max), osdMs)));
  calibration.textContent = `${linked ? '已联动' : '独立预览'} · OSD 时间 = 视频时间 ${offsetMs >= 0 ? '+' : '−'} ${formatTime(Math.abs(offsetMs))}`;
  alignment.textContent = `视频 ${formatTime(currentVideoMs())} ↔ OSD ${formatTime(osdMs)}`;
  unlinkButton.disabled = !linked;
}

function centerCamera(frame) {
  const height = cameraPlaced
    ? Math.max(Number(frame.height) + 150, viewer.camera.positionCartographic.height)
    : 450;
  viewer.camera.setView({
    destination: Cesium.Cartesian3.fromDegrees(
      Number(frame.longitude), Number(frame.latitude), height
    ),
    orientation: { heading: 0, pitch: -Cesium.Math.PI_OVER_TWO, roll: 0 }
  });
}

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
  if (footprint) viewer.scene.primitives.remove(footprint);
  if (frameBorder) viewer.entities.remove(frameBorder);
  frustumLines.forEach(line => viewer.entities.remove(line));
  footprint = undefined;
  frameBorder = undefined;
  frustumLines = [];
  activeCueIndex = -1;
}

function drawTrail(previewFrame) {
  if (trail) viewer.entities.remove(trail);
  trail = undefined;
  const points = activeCues
    .map(({ frame }) => [Number(frame.longitude), Number(frame.latitude), Number(frame.height)])
    .filter(([lon, lat, height]) => Number.isFinite(lon) && Number.isFinite(lat) && Number.isFinite(height))
    .map(([lon, lat, height]) => Cesium.Cartesian3.fromDegrees(lon, lat, height));
  if (points.length < 2) return;
  trail = viewer.entities.add({
    polyline: {
      positions: points,
      width: 5,
      material: new Cesium.PolylineOutlineMaterialProperty({
        color: Cesium.Color.fromCssColorString('#f39822'),
        outlineColor: Cesium.Color.fromCssColorString('#603816').withAlpha(0.8),
        outlineWidth: 1.5
      })
    }
  });
  if (!cameraPlaced) {
    centerCamera(previewFrame);
    cameraPlaced = true;
  }
}

// 按相机 TL→TR→BR→BL 顺序将视频映射到四角网格，和实时投影的 UV 方向一致。
function createProjection(corners) {
  const grid = 12;
  const vertices = [];
  const texture = [];
  const indices = [];
  for (let row = 0; row <= grid; row += 1) {
    const v = row / grid;
    for (let col = 0; col <= grid; col += 1) {
      const u = col / grid;
      const top = Cesium.Cartesian3.lerp(corners[0], corners[1], u, new Cesium.Cartesian3());
      const bottom = Cesium.Cartesian3.lerp(corners[3], corners[2], u, new Cesium.Cartesian3());
      vertices.push(Cesium.Cartesian3.lerp(top, bottom, v, new Cesium.Cartesian3()));
      texture.push(u, 1 - v);
    }
  }
  for (let row = 0; row < grid; row += 1) {
    for (let col = 0; col < grid; col += 1) {
      const tl = row * (grid + 1) + col;
      indices.push(tl, tl + 1, tl + grid + 2, tl, tl + grid + 2, tl + grid + 1);
    }
  }
  const attributes = new Cesium.GeometryAttributes();
  attributes.position = new Cesium.GeometryAttribute({
    componentDatatype: Cesium.ComponentDatatype.DOUBLE,
    componentsPerAttribute: 3,
    values: new Float64Array(vertices.flatMap(point => [point.x, point.y, point.z]))
  });
  attributes.st = new Cesium.GeometryAttribute({
    componentDatatype: Cesium.ComponentDatatype.FLOAT,
    componentsPerAttribute: 2,
    values: new Float32Array(texture)
  });
  const geometry = new Cesium.Geometry({
    attributes,
    indices: new Uint16Array(indices),
    primitiveType: Cesium.PrimitiveType.TRIANGLES,
    boundingSphere: Cesium.BoundingSphere.fromPoints(vertices)
  });
  return viewer.scene.primitives.add(new Cesium.Primitive({
    geometryInstances: new Cesium.GeometryInstance({ geometry }),
    appearance: new Cesium.MaterialAppearance({
      material: Cesium.Material.fromType('Image', { image: video }),
      faceForward: true,
      flat: true,
      closed: false
    }),
    asynchronous: false
  }));
}

function render() {
  if (!manifest || activeCues.length === 0) return;
  const lookupTime = effectiveOsdMs();
  updateTimeline();
  const index = cueAt(lookupTime);
  const needsVideoTexture = index >= 0 && activeCues[index].frame.viewMode === 'PLANAR'
    && !footprint && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA;
  if (index === activeCueIndex && index >= 0 && !needsVideoTexture) return;
  clearProjection();
  if (index < 0) {
    if (aircraft) aircraft.show = false;
    status.textContent = `OSD ${formatTime(lookupTime)} · 当前时间没有有效投影帧`;
    telemetry.textContent = '等待下一条视锥数据';
    return;
  }
  activeCueIndex = index;
  const frame = activeCues[index].frame;
  const corners = frame.viewMode === 'PLANAR' ? frame.corners : frame.spatialCorners;
  if (!Array.isArray(corners) || corners.length !== 4) {
    if (aircraft) aircraft.show = false;
    return;
  }
  const points = corners.map(point => Cesium.Cartesian3.fromDegrees(
    Number(point.longitude), Number(point.latitude),
    frame.viewMode === 'PLANAR' ? 0.35 : Number(point.height)
  ));
  if (!points.every(point => Number.isFinite(point.x) && Number.isFinite(point.y) && Number.isFinite(point.z))) {
    if (aircraft) aircraft.show = false;
    return;
  }
  const origin = Cesium.Cartesian3.fromDegrees(
    Number(frame.longitude), Number(frame.latitude), Number(frame.height)
  );
  frameBorder = viewer.entities.add({
    polyline: {
      positions: [...points, points[0]],
      width: 4,
      material: new Cesium.PolylineOutlineMaterialProperty({
        color: Cesium.Color.fromCssColorString('#13c9e1'),
        outlineColor: Cesium.Color.fromCssColorString('#07586e').withAlpha(0.9),
        outlineWidth: 1
      })
    }
  });
  frustumLines = points.map(point => viewer.entities.add({
    polyline: {
      positions: [origin, point],
      width: 2,
      material: new Cesium.PolylineDashMaterialProperty({
        color: Cesium.Color.fromCssColorString('#66d6a3').withAlpha(0.9),
        dashLength: 12
      })
    }
  }));
  // 与实时页一致：仅地面模式贴视频，空间模式显示视锥。
  if (frame.viewMode === 'PLANAR' && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
    footprint = createProjection(points);
  }
  const heading = Number(frame.attitudeHead);
  // The glTF nose is +X, which Cesium maps to local north before applying HPR.
  const orientation = Cesium.Transforms.headingPitchRollQuaternion(origin,
    new Cesium.HeadingPitchRoll(Cesium.Math.toRadians(Number.isFinite(heading) ? heading : 0), 0, 0));
  if (aircraft) {
    aircraft.position = origin;
    aircraft.orientation = orientation;
    aircraft.show = true;
  } else {
    aircraft = viewer.entities.add({
      position: origin,
      orientation,
      model: {
        uri: aircraftModel,
        minimumPixelSize: 56,
        maximumScale: 200,
        shadows: Cesium.ShadowMode.DISABLED,
        customShader: aircraftShader
      }
    });
  }
  if (followDrone.checked) centerCamera(frame);
  status.textContent = `OSD ${formatTime(lookupTime)} · `
    + (frame.viewMode === 'PLANAR' ? '地面投影' : '空间视锥');
  telemetry.textContent = `OSD 时间 ${new Date(frame.reportTimeMs).toLocaleString()} · cue ${index + 1}/${activeCues.length}`;
}

video.addEventListener('timeupdate', render);
video.addEventListener('seeked', render);
video.addEventListener('loadeddata', render);
video.addEventListener('pause', render);
followDrone.addEventListener('change', () => {
  followLabel.textContent = followDrone.checked ? '自动居中' : '手动浏览';
  if (!followDrone.checked || activeCueIndex < 0) return;
  centerCamera(activeCues[activeCueIndex].frame);
});
video.addEventListener('loadedmetadata', () => {
  if (linked && offsetMs < 0) {
    video.currentTime = Math.min(-offsetMs / 1000, video.duration);
  }
  updateTimeline();
  render();
});
osdSlider.addEventListener('input', () => {
  video.pause();
  linked = false;
  osdCursorMs = Number(osdSlider.value);
  clearProjection();
  render();
});
document.querySelectorAll('[data-osd-step]').forEach(button => {
  button.addEventListener('click', () => {
    video.pause();
    const currentOsd = effectiveOsdMs();
    linked = false;
    osdCursorMs = Math.max(0, Math.min(Number(osdSlider.max),
      currentOsd + Number(button.dataset.osdStep)));
    clearProjection();
    render();
  });
});
alignButton.addEventListener('click', () => {
  if (!activeCues.length || !Number.isFinite(video.duration)) return;
  offsetMs = osdCursorMs - currentVideoMs();
  localStorage.setItem(calibrationKey, String(offsetMs));
  linked = true;
  clearProjection();
  render();
});
unlinkButton.addEventListener('click', () => {
  osdCursorMs = effectiveOsdMs();
  linked = false;
  updateTimeline();
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
  if (manifest.segments?.length !== 1 || manifest.segments[0].videoRecordId !== 'drone-video-all') {
    throw new Error('校准模式需要 drone-video-all 单视频清单');
  }
  const segment = manifest.segments[0];
  const srtResponse = await fetch(new URL(segment.telemetryUrl, manifestUrl));
  if (!srtResponse.ok) throw new Error(`SRT HTTP ${srtResponse.status}`);
  activeCues = parseSrt(await srtResponse.text());
  if (!activeCues.length) throw new Error('SRT 没有有效投影帧');
  osdSlider.max = String(segment.durationMs);
  osdCursorMs = activeCues[0].start;
  const savedOffsetText = localStorage.getItem(calibrationKey);
  const configuredOffset = Number(segment.syncOffsetMs);
  if (Number.isFinite(configuredOffset)) offsetMs = configuredOffset;
  if (savedOffsetText !== null && Number.isFinite(Number(savedOffsetText))) {
    offsetMs = Number(savedOffsetText);
  }
  linked = savedOffsetText !== null || offsetMs !== 0;
  drawTrail(activeCues[0].frame);
  video.src = new URL(segment.videoUrl, manifestUrl).href;
  video.load();
  status.textContent = `完整 OSD 已加载：${activeCues.length} 条投影帧`;
  render();
} catch (error) {
  status.textContent = error.message;
}
