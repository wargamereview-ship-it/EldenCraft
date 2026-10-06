//! Minecraft's blocks and HUD drawn into Elden Ring's frame with DirectX 12, on the game's own
//! present queue, just before the frame is shown.
//!
//! Per frame: the scanned collision goes into a depth buffer of our own (so Elden Ring's terrain
//! and walls hide blocks behind them), then Minecraft's opaque and cutout blocks, its translucent
//! blocks, and last its GUI layer: premultiplied alpha, with the crosshair area drawn in a pass of
//! its own with Minecraft's invert blend. The view is the game camera's: Minecraft's eye, look
//! and field of view, the same values the camera task gives the game.

use std::collections::HashMap;
use std::mem::ManuallyDrop;
use std::sync::{Arc, Mutex, OnceLock};

use hudhook::util::{self, Fence};
use hudhook::windows::Win32::Graphics::Direct3D::Fxc::D3DCompile;
use hudhook::windows::Win32::Graphics::Direct3D::{ID3DBlob, ID3DInclude, D3D_PRIMITIVE_TOPOLOGY_LINELIST, D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST};
use hudhook::windows::Win32::Graphics::Direct3D12::*;
use hudhook::windows::Win32::Graphics::Dxgi::Common::*;
use hudhook::windows::Win32::Foundation::RECT;
use hudhook::windows::core::{Interface, PCSTR, Result};

use crate::link::Link;
use crate::log;
use crate::proto::{self, RenVertex, WorldEntity};
use crate::scene::{self, Atlas, Scene};

const NEAR: f32 = 0.05;
const FAR: f32 = 768.0;
/// Sections further than this (blocks) are not drawn.
const DRAW_DISTANCE: f64 = 128.0;
/// Collision further than this is not drawn into the depth buffer.
const OCCLUDE_DISTANCE: f64 = 64.0;

static LINK: OnceLock<Link> = OnceLock::new();
static RENDERER: Mutex<Option<Renderer>> = Mutex::new(None);
/// Renderer failures so far; after a few it stays off rather than failing every frame.
static FAILURES: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
const MAX_FAILURES: u32 = 3;

/// Hooks the renderer into the game's present (through hudhook).
pub fn install(link: Link) {
	let _ = LINK.set(link);
	hudhook::hooks::dx12::set_present_callback(Box::new(|device, queue, back_buffer, format| {
		use std::sync::atomic::Ordering::Relaxed;
		if FAILURES.load(Relaxed) >= MAX_FAILURES {
			return;
		}
		let mut guard = RENDERER.lock().unwrap_or_else(|e| e.into_inner());
		if guard.as_ref().is_some_and(|r| r.format != format) {
			*guard = None;
		}
		if guard.is_none() {
			match unsafe { Renderer::new(device, format) } {
				Ok(r) => {
					log::line(&format!("gpu: renderer ready (back buffer format {})", format.0));
					*guard = Some(r);
				}
				Err(e) => {
					log::line(&format!("gpu: couldn't create the renderer: {e}"));
					if FAILURES.fetch_add(1, Relaxed) + 1 >= MAX_FAILURES {
						log::line("gpu: giving up; no Minecraft blocks or HUD this session");
					}
					return;
				}
			}
		}
		if let Some(r) = guard.as_mut() {
			if let Err(e) = unsafe { r.frame(queue, back_buffer) } {
				log::line(&format!("gpu: frame failed: {e}; starting over"));
				*guard = None;
				if FAILURES.fetch_add(1, Relaxed) + 1 >= MAX_FAILURES {
					log::line("gpu: giving up; no Minecraft blocks or HUD this session");
				}
			}
		}
	}));
}

const SHADERS: &str = r#"
cbuffer C : register(b0) {
	float4 right; float4 up; float4 fwd; float4 eye;
	float4 proj;    // x scale, y scale, z scale, z offset
	float4 offset;  // blocks: what to add to positions; overlay: the invert rectangle (pixels)
	float4 params;  // blocks: y daylight; occluders: x push-back; overlay: x flip
	float4 sunDir;  // blocks: unit vector toward the sun (or moon)
	float4 sunCol;  // blocks: its colour
	float4 ambient; // blocks: colour of the light on every face
	float4 frame;   // blocks: the game's picture on screen (x, y, width, height in back buffer pixels)
	float4 look;    // blocks: x 1 = take light from the game's picture, y its reference brightness, z tint strength, w sample radius (pixels)
};
Texture2D<float4> frameTex : register(t1);
Texture2D tex : register(t0);
SamplerState samp : register(s0);

float4 Project(float3 p) {
	float xv = dot(p, right.xyz), yv = dot(p, up.xyz), zv = dot(p, fwd.xyz);
	return float4(xv * proj.x, yv * proj.y, zv * proj.z + proj.w, zv);
}

struct BIn { float3 pos : POSITION; float2 uv : TEXCOORD0; float4 col : COLOR0; uint light : LIGHT; uint flags : FLAGS; };
struct BOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; float4 col : COLOR0; float3 lit : LITCOL; nointerpolation uint flags : FLAGS; };

// The average colour of the game's picture around a point on screen, as a light level (1 is
// what a normally sunlit scene looks like) with a mild tint.
float3 GameLight(float4 clip) {
	float2 ndc = clip.xy / max(clip.w, 0.05);
	float2 c = frame.xy + float2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5) * frame.zw;
	float2 lo = frame.xy, hi = frame.xy + frame.zw - 1.0;
	float3 acc = float3(0, 0, 0);
	[unroll] for (int k = 0; k < 32; k++) {
		float a = k * 2.39996323;
		float r = sqrt((k + 0.5) / 32.0) * look.w;
		float2 p = clamp(c + float2(cos(a), sin(a)) * r, lo, hi);
		acc += frameTex.Load(int3(int2(p), 0)).rgb;
	}
	float3 avg = acc / 32.0;
	float luma = dot(avg, float3(0.299, 0.587, 0.114));
	float3 tint = lerp(float3(1, 1, 1), clamp(avg / max(luma, 0.02), 0.6, 1.6), look.z);
	return clamp(luma / look.y, 0.05, 1.3) * tint;
}

BOut VSBlock(BIn i) {
	BOut o;
	o.pos = Project(i.pos + offset.xyz - eye.xyz);
	o.uv = i.uv;
	o.col = i.col;
	o.flags = i.flags;
	float block = (i.light & 15) / 15.0;
	float sky = ((i.light >> 8) & 15) / 15.0;
	float sl = sky * params.y;
	sl = sl / (4.0 - 3.0 * sl);  // Minecraft's brightness curve
	float bl = block / (4.0 - 3.0 * block);
	// Face codes: 1 down, 2 up, 3 north, 4 south, 5 west, 6 east (0: no face, as for entities).
	uint face = (i.flags >> 4) & 7;
	float3 n = face == 1 ? float3(0, -1, 0) : face == 3 ? float3(0, 0, -1) : face == 4 ? float3(0, 0, 1)
		: face == 5 ? float3(-1, 0, 0) : face == 6 ? float3(1, 0, 0) : float3(0, 1, 0);
	float shade = face == 1 ? 0.55 : face == 2 ? 1.0 : face == 0 ? 0.9 : 0.8;
	float facing = face == 0 ? 0.6 : saturate(dot(n, sunDir.xyz));
	float3 blockLight = float3(1.0, 0.78, 0.5) * bl * shade;
	float3 skyLight;
	if (look.x > 0.5) {
		// The light of this spot as the game itself drew it: shadows, fog, weather, colour grading,
		// lamps and how bright the place is all show in the picture around it.
		float3 here = GameLight(o.pos);
		// How much dimmer this face is than a face looking straight up, from the sun and sky.
		float3 w = float3(0.299, 0.587, 0.114);
		float faceLit = dot(ambient.rgb * shade + sunCol.rgb * facing, w);
		float topLit = dot(ambient.rgb + sunCol.rgb * saturate(sunDir.y), w);
		skyLight = here * clamp(faceLit / max(topLit, 0.02), 0.3, 1.0) * sl;
	} else {
		// Minecraft's sky light, shaded by the clock's sun and ambient.
		skyLight = (ambient.rgb * shade + sunCol.rgb * facing) * sl;
	}
	o.lit = clamp(skyLight + blockLight, 0.05, 1.25);
	return o;
}

float4 PSBlock(BOut i) : SV_Target {
	float4 t = tex.Sample(samp, i.uv) * i.col;
	if ((i.flags & 1) != 0 && t.a < 0.5) discard;
	return float4(t.rgb * i.lit, (i.flags & 2) != 0 ? t.a : 1.0);
}

// Contact shadow: a soft dark disc over the quad.
float4 PSShadow(BOut i) : SV_Target {
	float2 d = i.uv * 2 - 1;
	float a = saturate(1 - dot(d, d));
	return float4(0, 0, 0, 0.45 * a * a);
}

// The outline around the block Minecraft targets.
float4 PSLine(BOut i) : SV_Target { return float4(0, 0, 0, 0.5); }

float4 VSOccluder(float3 pos : POSITION) : SV_Position {
	float3 p = pos + offset.xyz - eye.xyz;
	// A little further away than it is: the collision is only near the visible ground, and a
	// block resting on it must not be cut by it.
	p += normalize(p) * params.x;
	return Project(p);
}

struct OOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
OOut VSFull(uint id : SV_VertexID) {
	OOut o;
	float2 uv = float2((id << 1) & 2, id & 2);
	o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
	o.uv = uv;
	return o;
}
bool InRect(float2 p) { return all(p >= offset.xy) && all(p < offset.zw); }
float4 Overlay(float2 uv) {
	if (params.x > 0.5) uv.y = 1 - uv.y;
	return tex.Sample(samp, uv);  // premultiplied alpha straight from Minecraft
}
float4 PSOverlay(OOut i) : SV_Target {
	if (InRect(i.pos.xy)) return 0;
	float4 c = Overlay(i.uv);
	// The mouse cursor over an open Minecraft screen (right.xy, right.w = its size).
	if (right.z > 0.5) {
		float2 p = (i.pos.xy - right.xy) / right.w;
		if (p.x >= 0 && p.y >= 0 && p.y < 18 && p.x <= p.y * 0.6) {
			bool edge = p.x < 1.5 || p.x > p.y * 0.6 - 1.5 || p.y > 16.5;
			c = float4(edge ? float3(0, 0, 0) : float3(1, 1, 1), 1);
		}
	}
	return c;
}
// Minecraft's crosshair: out = src * (1 - dst) + dst * (1 - src), against Elden Ring's picture.
float4 PSInvert(OOut i) : SV_Target {
	if (!InRect(i.pos.xy)) discard;
	return float4(Overlay(i.uv).rgb, 0);
}
"#;

const CONSTANTS: u32 = 48;
/// Descriptor slots: 0 the block atlas, 1 the GUI, then Minecraft's entity textures by id.
const SRV_SLOTS: u32 = 65;
/// The copy of the game's picture (blocks take their light from it).
const FRAME_SLOT: u32 = 64;

fn texture_slot(id: u32) -> Option<u32> {
	(id >= 1 && id + 1 < FRAME_SLOT).then_some(id + 1)
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Constants {
	right: [f32; 4],
	up: [f32; 4],
	fwd: [f32; 4],
	eye: [f32; 4],
	proj: [f32; 4],
	offset: [f32; 4],
	params: [f32; 4],
	sun_dir: [f32; 4],
	sun_col: [f32; 4],
	ambient: [f32; 4],
	frame: [f32; 4],
	look: [f32; 4],
}

struct FrameCopy {
	resource: ID3D12Resource,
	width: u32,
	height: u32,
	format: DXGI_FORMAT,
	/// Has been copied into once (it is in the shader-resource state between frames).
	ready: bool,
}

/// What a vertex shader (or any) reads the copy of the game's picture in.
const FRAME_READ: D3D12_RESOURCE_STATES = D3D12_RESOURCE_STATES(D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE.0 | D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE.0);

struct Mesh {
	source: Arc<Vec<RenVertex>>,
	buffer: ID3D12Resource,
	opaque: u32,
	translucent: u32,
}

struct Occluder {
	source: Arc<Vec<[f32; 3]>>,
	buffer: ID3D12Resource,
	count: u32,
}

struct Texture {
	resource: ID3D12Resource,
	upload: ID3D12Resource,
	width: u32,
	height: u32,
	pitch: u32,
	/// Has been copied to once (it is in the pixel-shader state between uploads).
	ready: bool,
}

// The renderer only ever runs on the game's present, behind RENDERER's lock.
unsafe impl Send for Renderer {}

struct Renderer {
	device: ID3D12Device,
	format: DXGI_FORMAT,
	allocator: ID3D12CommandAllocator,
	list: ID3D12GraphicsCommandList,
	fence: Fence,
	in_flight: Option<u64>,
	root: ID3D12RootSignature,
	pso_occluder: ID3D12PipelineState,
	pso_opaque: ID3D12PipelineState,
	pso_translucent: ID3D12PipelineState,
	pso_overlay: ID3D12PipelineState,
	pso_invert: ID3D12PipelineState,
	pso_shadow: ID3D12PipelineState,
	pso_lines: ID3D12PipelineState,
	rtv_heap: ID3D12DescriptorHeap,
	dsv_heap: ID3D12DescriptorHeap,
	srv_heap: ID3D12DescriptorHeap,
	srv_step: u32,
	depth: Option<(ID3D12Resource, u32, u32)>,
	atlas: Option<(Texture, Arc<Atlas>)>,
	overlay: Option<Texture>,
	overlay_front: usize,
	overlay_flip: bool,
	/// A copy of the game's picture taken before drawing, and whether making one has failed for good.
	frame_copy: Option<FrameCopy>,
	frame_failed: bool,
	meshes: HashMap<[i32; 3], Mesh>,
	/// Entity textures by Minecraft's id, and what they were made from.
	entity_textures: HashMap<u32, (Texture, Arc<Atlas>)>,
	/// This frame's entities (rebuilt every frame) and its capacity in vertices.
	dynamic: Option<(ID3D12Resource, usize)>,
	occluders: HashMap<(i32, i32), Occluder>,
	/// Resources the GPU may still read; freed once the frame in flight is done.
	garbage: Vec<ID3D12Resource>,
}

fn compile(entry: &str, target: &str) -> Result<ID3DBlob> {
	let entry = format!("{entry}\0");
	let target = format!("{target}\0");
	util::try_out_err_blob(|v, err| unsafe {
		D3DCompile(
			SHADERS.as_ptr() as _,
			SHADERS.len(),
			None,
			None,
			None::<&ID3DInclude>,
			PCSTR(entry.as_ptr()),
			PCSTR(target.as_ptr()),
			0,
			0,
			v,
			Some(err),
		)
	})
	.map_err(|(e, blob): (hudhook::windows::core::Error, ID3DBlob)| {
		let msg = unsafe { std::slice::from_raw_parts(blob.GetBufferPointer() as *const u8, blob.GetBufferSize()) };
		log::line(&format!("gpu: shader {entry} failed: {}", String::from_utf8_lossy(msg)));
		e
	})
}

fn bytecode(blob: &Option<ID3DBlob>) -> D3D12_SHADER_BYTECODE {
	match blob {
		Some(b) => unsafe { D3D12_SHADER_BYTECODE { pShaderBytecode: b.GetBufferPointer(), BytecodeLength: b.GetBufferSize() } },
		None => D3D12_SHADER_BYTECODE::default(),
	}
}

fn element(name: &'static [u8], format: DXGI_FORMAT, offset: u32) -> D3D12_INPUT_ELEMENT_DESC {
	D3D12_INPUT_ELEMENT_DESC {
		SemanticName: PCSTR(name.as_ptr()),
		SemanticIndex: 0,
		Format: format,
		InputSlot: 0,
		AlignedByteOffset: offset,
		InputSlotClass: D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA,
		InstanceDataStepRate: 0,
	}
}

#[derive(Clone, Copy)]
enum Blend {
	None,
	Alpha,
	Premultiplied,
	Invert,
}

struct PsoSpec<'a> {
	lines: bool,
	vs: &'a Option<ID3DBlob>,
	ps: &'a Option<ID3DBlob>,
	inputs: &'a [D3D12_INPUT_ELEMENT_DESC],
	depth_test: bool,
	depth_write: bool,
	color_write: bool,
	blend: Blend,
}

impl Renderer {
	unsafe fn new(device: &ID3D12Device, format: DXGI_FORMAT) -> Result<Self> {
		unsafe {
			let allocator: ID3D12CommandAllocator = device.CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT)?;
			let list: ID3D12GraphicsCommandList = device.CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, &allocator, None)?;
			list.Close()?;
			let fence = Fence::new(device)?;

			let params = [
				D3D12_ROOT_PARAMETER {
					ParameterType: D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS,
					Anonymous: D3D12_ROOT_PARAMETER_0 { Constants: D3D12_ROOT_CONSTANTS { ShaderRegister: 0, RegisterSpace: 0, Num32BitValues: CONSTANTS } },
					ShaderVisibility: D3D12_SHADER_VISIBILITY_ALL,
				},
				D3D12_ROOT_PARAMETER {
					ParameterType: D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE,
					Anonymous: D3D12_ROOT_PARAMETER_0 {
						DescriptorTable: D3D12_ROOT_DESCRIPTOR_TABLE {
							NumDescriptorRanges: 1,
							pDescriptorRanges: &D3D12_DESCRIPTOR_RANGE {
								RangeType: D3D12_DESCRIPTOR_RANGE_TYPE_SRV,
								NumDescriptors: 1,
								BaseShaderRegister: 0,
								RegisterSpace: 0,
								OffsetInDescriptorsFromTableStart: 0,
							},
						},
					},
					ShaderVisibility: D3D12_SHADER_VISIBILITY_PIXEL,
				},
				// The game's picture, read by the block vertex shader.
				D3D12_ROOT_PARAMETER {
					ParameterType: D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE,
					Anonymous: D3D12_ROOT_PARAMETER_0 {
						DescriptorTable: D3D12_ROOT_DESCRIPTOR_TABLE {
							NumDescriptorRanges: 1,
							pDescriptorRanges: &D3D12_DESCRIPTOR_RANGE {
								RangeType: D3D12_DESCRIPTOR_RANGE_TYPE_SRV,
								NumDescriptors: 1,
								BaseShaderRegister: 1,
								RegisterSpace: 0,
								OffsetInDescriptorsFromTableStart: 0,
							},
						},
					},
					ShaderVisibility: D3D12_SHADER_VISIBILITY_VERTEX,
				},
			];
			let root_desc = D3D12_ROOT_SIGNATURE_DESC {
				NumParameters: params.len() as u32,
				pParameters: params.as_ptr(),
				NumStaticSamplers: 1,
				pStaticSamplers: &D3D12_STATIC_SAMPLER_DESC {
					Filter: D3D12_FILTER_MIN_MAG_MIP_POINT,
					AddressU: D3D12_TEXTURE_ADDRESS_MODE_CLAMP,
					AddressV: D3D12_TEXTURE_ADDRESS_MODE_CLAMP,
					AddressW: D3D12_TEXTURE_ADDRESS_MODE_CLAMP,
					MipLODBias: 0.0,
					MaxAnisotropy: 0,
					ComparisonFunc: D3D12_COMPARISON_FUNC_ALWAYS,
					BorderColor: D3D12_STATIC_BORDER_COLOR_TRANSPARENT_BLACK,
					MinLOD: 0.0,
					MaxLOD: 0.0,
					ShaderRegister: 0,
					RegisterSpace: 0,
					ShaderVisibility: D3D12_SHADER_VISIBILITY_PIXEL,
				},
				Flags: D3D12_ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT,
			};
			let blob: ID3DBlob = util::try_out_err_blob(|v, err| D3D12SerializeRootSignature(&root_desc, D3D_ROOT_SIGNATURE_VERSION_1_0, v, Some(err)))
				.map_err(|(e, _): (hudhook::windows::core::Error, ID3DBlob)| e)?;
			let root: ID3D12RootSignature =
				device.CreateRootSignature(0, std::slice::from_raw_parts(blob.GetBufferPointer() as *const u8, blob.GetBufferSize()))?;

			let vs_block = Some(compile("VSBlock", "vs_5_0")?);
			let ps_block = Some(compile("PSBlock", "ps_5_0")?);
			let vs_occluder = Some(compile("VSOccluder", "vs_5_0")?);
			let vs_full = Some(compile("VSFull", "vs_5_0")?);
			let ps_overlay = Some(compile("PSOverlay", "ps_5_0")?);
			let ps_invert = Some(compile("PSInvert", "ps_5_0")?);
			let ps_shadow = Some(compile("PSShadow", "ps_5_0")?);
			let ps_line = Some(compile("PSLine", "ps_5_0")?);

			let block_inputs = [
				element(b"POSITION\0", DXGI_FORMAT_R32G32B32_FLOAT, 0),
				element(b"TEXCOORD\0", DXGI_FORMAT_R32G32_FLOAT, 12),
				element(b"COLOR\0", DXGI_FORMAT_R8G8B8A8_UNORM, 20),
				element(b"LIGHT\0", DXGI_FORMAT_R32_UINT, 24),
				element(b"FLAGS\0", DXGI_FORMAT_R32_UINT, 28),
			];
			let occluder_inputs = [element(b"POSITION\0", DXGI_FORMAT_R32G32B32_FLOAT, 0)];

			let pso = |spec: PsoSpec| -> Result<ID3D12PipelineState> {
				let blend_target = match spec.blend {
					Blend::None => D3D12_RENDER_TARGET_BLEND_DESC { BlendEnable: false.into(), ..Default::default() },
					Blend::Alpha => D3D12_RENDER_TARGET_BLEND_DESC {
						BlendEnable: true.into(),
						SrcBlend: D3D12_BLEND_SRC_ALPHA,
						DestBlend: D3D12_BLEND_INV_SRC_ALPHA,
						BlendOp: D3D12_BLEND_OP_ADD,
						SrcBlendAlpha: D3D12_BLEND_ZERO,
						DestBlendAlpha: D3D12_BLEND_ONE,
						BlendOpAlpha: D3D12_BLEND_OP_ADD,
						..Default::default()
					},
					Blend::Premultiplied => D3D12_RENDER_TARGET_BLEND_DESC {
						BlendEnable: true.into(),
						SrcBlend: D3D12_BLEND_ONE,
						DestBlend: D3D12_BLEND_INV_SRC_ALPHA,
						BlendOp: D3D12_BLEND_OP_ADD,
						SrcBlendAlpha: D3D12_BLEND_ZERO,
						DestBlendAlpha: D3D12_BLEND_ONE,
						BlendOpAlpha: D3D12_BLEND_OP_ADD,
						..Default::default()
					},
					Blend::Invert => D3D12_RENDER_TARGET_BLEND_DESC {
						BlendEnable: true.into(),
						SrcBlend: D3D12_BLEND_INV_DEST_COLOR,
						DestBlend: D3D12_BLEND_INV_SRC_COLOR,
						BlendOp: D3D12_BLEND_OP_ADD,
						SrcBlendAlpha: D3D12_BLEND_ZERO,
						DestBlendAlpha: D3D12_BLEND_ONE,
						BlendOpAlpha: D3D12_BLEND_OP_ADD,
						..Default::default()
					},
				};
				let mut targets = [D3D12_RENDER_TARGET_BLEND_DESC::default(); 8];
				targets[0] = D3D12_RENDER_TARGET_BLEND_DESC {
					LogicOpEnable: false.into(),
					LogicOp: D3D12_LOGIC_OP_NOOP,
					// The game's alpha channel is left as it is.
					RenderTargetWriteMask: if spec.color_write { (D3D12_COLOR_WRITE_ENABLE_ALL.0 & !D3D12_COLOR_WRITE_ENABLE_ALPHA.0) as u8 } else { 0 },
					..blend_target
				};
				let mut rtv_formats = [DXGI_FORMAT_UNKNOWN; 8];
				rtv_formats[0] = format;
				let desc = D3D12_GRAPHICS_PIPELINE_STATE_DESC {
					pRootSignature: ManuallyDrop::new(Some(root.clone())),
					VS: bytecode(spec.vs),
					PS: bytecode(spec.ps),
					BlendState: D3D12_BLEND_DESC { AlphaToCoverageEnable: false.into(), IndependentBlendEnable: false.into(), RenderTarget: targets },
					SampleMask: u32::MAX,
					RasterizerState: D3D12_RASTERIZER_DESC {
						FillMode: D3D12_FILL_MODE_SOLID,
						CullMode: D3D12_CULL_MODE_NONE,
						FrontCounterClockwise: false.into(),
						DepthBias: 0,
						DepthBiasClamp: 0.0,
						SlopeScaledDepthBias: 0.0,
						DepthClipEnable: true.into(),
						MultisampleEnable: false.into(),
						AntialiasedLineEnable: false.into(),
						ForcedSampleCount: 0,
						ConservativeRaster: D3D12_CONSERVATIVE_RASTERIZATION_MODE_OFF,
					},
					DepthStencilState: D3D12_DEPTH_STENCIL_DESC {
						DepthEnable: spec.depth_test.into(),
						DepthWriteMask: if spec.depth_write { D3D12_DEPTH_WRITE_MASK_ALL } else { D3D12_DEPTH_WRITE_MASK_ZERO },
						DepthFunc: D3D12_COMPARISON_FUNC_LESS,
						StencilEnable: false.into(),
						..Default::default()
					},
					InputLayout: D3D12_INPUT_LAYOUT_DESC { pInputElementDescs: spec.inputs.as_ptr(), NumElements: spec.inputs.len() as u32 },
					PrimitiveTopologyType: if spec.lines { D3D12_PRIMITIVE_TOPOLOGY_TYPE_LINE } else { D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE },
					NumRenderTargets: 1,
					RTVFormats: rtv_formats,
					DSVFormat: DXGI_FORMAT_D32_FLOAT,
					SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
					..Default::default()
				};
				let state = device.CreateGraphicsPipelineState(&desc);
				let _ = ManuallyDrop::into_inner(desc.pRootSignature);
				state
			};

			let none: Option<ID3DBlob> = None;
			let pso_occluder = pso(PsoSpec { lines: false, vs: &vs_occluder, ps: &none, inputs: &occluder_inputs, depth_test: true, depth_write: true, color_write: false, blend: Blend::None })?;
			let pso_opaque = pso(PsoSpec { lines: false, vs: &vs_block, ps: &ps_block, inputs: &block_inputs, depth_test: true, depth_write: true, color_write: true, blend: Blend::None })?;
			let pso_translucent = pso(PsoSpec { lines: false, vs: &vs_block, ps: &ps_block, inputs: &block_inputs, depth_test: true, depth_write: false, color_write: true, blend: Blend::Alpha })?;
			let pso_overlay = pso(PsoSpec { lines: false, vs: &vs_full, ps: &ps_overlay, inputs: &[], depth_test: false, depth_write: false, color_write: true, blend: Blend::Premultiplied })?;
			let pso_shadow = pso(PsoSpec { lines: false, vs: &vs_block, ps: &ps_shadow, inputs: &block_inputs, depth_test: true, depth_write: false, color_write: true, blend: Blend::Alpha })?;
			let pso_lines = pso(PsoSpec { lines: true, vs: &vs_block, ps: &ps_line, inputs: &block_inputs, depth_test: true, depth_write: false, color_write: true, blend: Blend::Alpha })?;
			let pso_invert = pso(PsoSpec { lines: false, vs: &vs_full, ps: &ps_invert, inputs: &[], depth_test: false, depth_write: false, color_write: true, blend: Blend::Invert })?;

			let heap = |kind, count, flags| device.CreateDescriptorHeap::<ID3D12DescriptorHeap>(&D3D12_DESCRIPTOR_HEAP_DESC { Type: kind, NumDescriptors: count, Flags: flags, NodeMask: 0 });
			let rtv_heap = heap(D3D12_DESCRIPTOR_HEAP_TYPE_RTV, 1, D3D12_DESCRIPTOR_HEAP_FLAG_NONE)?;
			let dsv_heap = heap(D3D12_DESCRIPTOR_HEAP_TYPE_DSV, 1, D3D12_DESCRIPTOR_HEAP_FLAG_NONE)?;
			let srv_heap = heap(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, SRV_SLOTS, D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE)?;
			let srv_step = device.GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
			// Until a copy of the game's picture exists the table still points at something valid.
			{
				let mut handle = srv_heap.GetCPUDescriptorHandleForHeapStart();
				handle.ptr += (FRAME_SLOT * srv_step) as usize;
				device.CreateShaderResourceView(
					None,
					Some(&D3D12_SHADER_RESOURCE_VIEW_DESC {
						Format: DXGI_FORMAT_R8G8B8A8_UNORM,
						ViewDimension: D3D12_SRV_DIMENSION_TEXTURE2D,
						Shader4ComponentMapping: D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING,
						Anonymous: D3D12_SHADER_RESOURCE_VIEW_DESC_0 { Texture2D: D3D12_TEX2D_SRV { MipLevels: 1, ..Default::default() } },
					}),
					handle,
				);
			}

			Ok(Self {
				device: device.clone(),
				format,
				allocator,
				list,
				fence,
				in_flight: None,
				root,
				pso_occluder,
				pso_opaque,
				pso_translucent,
				pso_overlay,
				pso_invert,
				pso_shadow,
				pso_lines,
				rtv_heap,
				dsv_heap,
				srv_heap,
				srv_step,
				depth: None,
				atlas: None,
				overlay: None,
				overlay_front: 2,
				overlay_flip: false,
				frame_copy: None,
				frame_failed: false,
				meshes: HashMap::new(),
				entity_textures: HashMap::new(),
				dynamic: None,
				occluders: HashMap::new(),
				garbage: Vec::new(),
			})
		}
	}

	unsafe fn buffer(&self, bytes: usize) -> Result<ID3D12Resource> {
		util::try_out_ptr(|v| unsafe {
			self.device.CreateCommittedResource(
				&D3D12_HEAP_PROPERTIES { Type: D3D12_HEAP_TYPE_UPLOAD, ..Default::default() },
				D3D12_HEAP_FLAG_NONE,
				&D3D12_RESOURCE_DESC {
					Dimension: D3D12_RESOURCE_DIMENSION_BUFFER,
					Width: bytes.max(256) as u64,
					Height: 1,
					DepthOrArraySize: 1,
					MipLevels: 1,
					SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
					Layout: D3D12_TEXTURE_LAYOUT_ROW_MAJOR,
					..Default::default()
				},
				D3D12_RESOURCE_STATE_GENERIC_READ,
				None,
				v,
			)
		})
	}

	unsafe fn fill(buffer: &ID3D12Resource, bytes: &[u8]) -> Result<()> {
		unsafe {
			let mut ptr = std::ptr::null_mut();
			buffer.Map(0, None, Some(&mut ptr))?;
			std::ptr::copy_nonoverlapping(bytes.as_ptr(), ptr as *mut u8, bytes.len());
			buffer.Unmap(0, None);
		}
		Ok(())
	}

	/// A shader-visible RGBA8 texture in descriptor slot `slot`, with an upload buffer to fill it.
	unsafe fn texture(&self, width: u32, height: u32, slot: u32) -> Result<Texture> {
		unsafe {
			let resource: ID3D12Resource = util::try_out_ptr(|v| {
				self.device.CreateCommittedResource(
					&D3D12_HEAP_PROPERTIES { Type: D3D12_HEAP_TYPE_DEFAULT, ..Default::default() },
					D3D12_HEAP_FLAG_NONE,
					&D3D12_RESOURCE_DESC {
						Dimension: D3D12_RESOURCE_DIMENSION_TEXTURE2D,
						Width: width as u64,
						Height: height,
						DepthOrArraySize: 1,
						MipLevels: 1,
						Format: DXGI_FORMAT_R8G8B8A8_UNORM,
						SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
						Layout: D3D12_TEXTURE_LAYOUT_UNKNOWN,
						..Default::default()
					},
					D3D12_RESOURCE_STATE_COPY_DEST,
					None,
					v,
				)
			})?;
			let pitch = (width * 4).div_ceil(D3D12_TEXTURE_DATA_PITCH_ALIGNMENT) * D3D12_TEXTURE_DATA_PITCH_ALIGNMENT;
			let upload = self.buffer(pitch as usize * height as usize)?;
			let mut handle = self.srv_heap.GetCPUDescriptorHandleForHeapStart();
			handle.ptr += (slot * self.srv_step) as usize;
			self.device.CreateShaderResourceView(
				&resource,
				Some(&D3D12_SHADER_RESOURCE_VIEW_DESC {
					Format: DXGI_FORMAT_R8G8B8A8_UNORM,
					ViewDimension: D3D12_SRV_DIMENSION_TEXTURE2D,
					Shader4ComponentMapping: D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING,
					Anonymous: D3D12_SHADER_RESOURCE_VIEW_DESC_0 { Texture2D: D3D12_TEX2D_SRV { MipLevels: 1, ..Default::default() } },
				}),
				handle,
			);
			Ok(Texture { resource, upload, width, height, pitch, ready: false })
		}
	}

	/// Copies `pixels` (tightly packed rows, top row first unless `flip`) into the texture.
	unsafe fn upload(&self, t: &mut Texture, pixels: &[u8]) -> Result<()> {
		unsafe {
			let mut ptr = std::ptr::null_mut();
			t.upload.Map(0, None, Some(&mut ptr))?;
			let row = t.width as usize * 4;
			for y in 0..t.height as usize {
				std::ptr::copy_nonoverlapping(pixels.as_ptr().add(y * row), (ptr as *mut u8).add(y * t.pitch as usize), row);
			}
			t.upload.Unmap(0, None);
			if t.ready {
				self.barrier(&t.resource, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, D3D12_RESOURCE_STATE_COPY_DEST);
			}
			let dst = D3D12_TEXTURE_COPY_LOCATION {
				pResource: ManuallyDrop::new(Some(t.resource.clone())),
				Type: D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX,
				Anonymous: D3D12_TEXTURE_COPY_LOCATION_0 { SubresourceIndex: 0 },
			};
			let src = D3D12_TEXTURE_COPY_LOCATION {
				pResource: ManuallyDrop::new(Some(t.upload.clone())),
				Type: D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT,
				Anonymous: D3D12_TEXTURE_COPY_LOCATION_0 {
					PlacedFootprint: D3D12_PLACED_SUBRESOURCE_FOOTPRINT {
						Offset: 0,
						Footprint: D3D12_SUBRESOURCE_FOOTPRINT { Format: DXGI_FORMAT_R8G8B8A8_UNORM, Width: t.width, Height: t.height, Depth: 1, RowPitch: t.pitch },
					},
				},
			};
			self.list.CopyTextureRegion(&dst, 0, 0, 0, &src, None);
			let _ = ManuallyDrop::into_inner(dst.pResource);
			let _ = ManuallyDrop::into_inner(src.pResource);
			self.barrier(&t.resource, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
			t.ready = true;
		}
		Ok(())
	}

	/// Makes (or resizes) the texture the game's picture is copied into. False when this back
	/// buffer's format cannot be copied and read, in which case blocks keep the clock's light.
	unsafe fn ensure_frame_copy(&mut self, desc: &D3D12_RESOURCE_DESC) -> Result<bool> {
		if desc.SampleDesc.Count > 1 {
			return Ok(false);
		}
		let (typeless, read) = match desc.Format {
			DXGI_FORMAT_R8G8B8A8_UNORM | DXGI_FORMAT_R8G8B8A8_UNORM_SRGB | DXGI_FORMAT_R8G8B8A8_TYPELESS => (DXGI_FORMAT_R8G8B8A8_TYPELESS, DXGI_FORMAT_R8G8B8A8_UNORM),
			DXGI_FORMAT_B8G8R8A8_UNORM | DXGI_FORMAT_B8G8R8A8_UNORM_SRGB | DXGI_FORMAT_B8G8R8A8_TYPELESS => (DXGI_FORMAT_B8G8R8A8_TYPELESS, DXGI_FORMAT_B8G8R8A8_UNORM),
			DXGI_FORMAT_R10G10B10A2_UNORM | DXGI_FORMAT_R10G10B10A2_TYPELESS => (DXGI_FORMAT_R10G10B10A2_TYPELESS, DXGI_FORMAT_R10G10B10A2_UNORM),
			DXGI_FORMAT_R16G16B16A16_FLOAT => (DXGI_FORMAT_R16G16B16A16_FLOAT, DXGI_FORMAT_R16G16B16A16_FLOAT),
			_ => return Ok(false),
		};
		let (width, height) = (desc.Width as u32, desc.Height);
		if self.frame_copy.as_ref().is_some_and(|f| f.width == width && f.height == height && f.format == desc.Format) {
			return Ok(true);
		}
		unsafe {
			let resource: ID3D12Resource = util::try_out_ptr(|v| {
				self.device.CreateCommittedResource(
					&D3D12_HEAP_PROPERTIES { Type: D3D12_HEAP_TYPE_DEFAULT, ..Default::default() },
					D3D12_HEAP_FLAG_NONE,
					&D3D12_RESOURCE_DESC {
						Dimension: D3D12_RESOURCE_DIMENSION_TEXTURE2D,
						Width: width as u64,
						Height: height,
						DepthOrArraySize: 1,
						MipLevels: 1,
						Format: typeless,
						SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
						Layout: D3D12_TEXTURE_LAYOUT_UNKNOWN,
						..Default::default()
					},
					D3D12_RESOURCE_STATE_COPY_DEST,
					None,
					v,
				)
			})?;
			let mut handle = self.srv_heap.GetCPUDescriptorHandleForHeapStart();
			handle.ptr += (FRAME_SLOT * self.srv_step) as usize;
			self.device.CreateShaderResourceView(
				&resource,
				Some(&D3D12_SHADER_RESOURCE_VIEW_DESC {
					Format: read,
					ViewDimension: D3D12_SRV_DIMENSION_TEXTURE2D,
					Shader4ComponentMapping: D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING,
					Anonymous: D3D12_SHADER_RESOURCE_VIEW_DESC_0 { Texture2D: D3D12_TEX2D_SRV { MipLevels: 1, ..Default::default() } },
				}),
				handle,
			);
			if let Some(old) = self.frame_copy.replace(FrameCopy { resource, width, height, format: desc.Format, ready: false }) {
				self.garbage.push(old.resource);
			}
		}
		log::line(&format!("gpu: copying the game's picture ({width}x{height}, format {}) for block lighting", desc.Format.0));
		Ok(true)
	}

	unsafe fn barrier(&self, resource: &ID3D12Resource, before: D3D12_RESOURCE_STATES, after: D3D12_RESOURCE_STATES) {
		let barriers = [util::create_barrier(resource, before, after)];
		unsafe { self.list.ResourceBarrier(&barriers) };
		barriers.into_iter().for_each(util::drop_barrier);
	}

	unsafe fn ensure_depth(&mut self, width: u32, height: u32) -> Result<()> {
		if self.depth.as_ref().is_some_and(|(_, w, h)| *w == width && *h == height) {
			return Ok(());
		}
		unsafe {
			let resource: ID3D12Resource = util::try_out_ptr(|v| {
				self.device.CreateCommittedResource(
					&D3D12_HEAP_PROPERTIES { Type: D3D12_HEAP_TYPE_DEFAULT, ..Default::default() },
					D3D12_HEAP_FLAG_NONE,
					&D3D12_RESOURCE_DESC {
						Dimension: D3D12_RESOURCE_DIMENSION_TEXTURE2D,
						Width: width as u64,
						Height: height,
						DepthOrArraySize: 1,
						MipLevels: 1,
						Format: DXGI_FORMAT_D32_FLOAT,
						SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
						Layout: D3D12_TEXTURE_LAYOUT_UNKNOWN,
						Flags: D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL,
						..Default::default()
					},
					D3D12_RESOURCE_STATE_DEPTH_WRITE,
					Some(&D3D12_CLEAR_VALUE {
						Format: DXGI_FORMAT_D32_FLOAT,
						Anonymous: D3D12_CLEAR_VALUE_0 { DepthStencil: D3D12_DEPTH_STENCIL_VALUE { Depth: 1.0, Stencil: 0 } },
					}),
					v,
				)
			})?;
			self.device.CreateDepthStencilView(&resource, None, self.dsv_heap.GetCPUDescriptorHandleForHeapStart());
			if let Some((old, _, _)) = self.depth.replace((resource, width, height)) {
				self.garbage.push(old);
			}
		}
		Ok(())
	}

	/// Brings the GPU copies of meshes and collision up to date with the scene.
	unsafe fn sync_buffers(&mut self, scene: &Scene) -> Result<()> {
		let gone: Vec<[i32; 3]> = self.meshes.keys().filter(|k| !scene.sections.contains_key(*k)).copied().collect();
		for k in gone {
			let m = self.meshes.remove(&k).unwrap();
			self.garbage.push(m.buffer);
		}
		for (k, verts) in &scene.sections {
			if self.meshes.get(k).is_some_and(|m| Arc::ptr_eq(&m.source, verts)) {
				continue;
			}
			// Opaque and cutout triangles first, then translucent ones (drawn in their own pass).
			let mut ordered: Vec<RenVertex> = Vec::with_capacity(verts.len());
			ordered.extend(verts.chunks_exact(3).filter(|t| t[0].flags & 2 == 0).flatten());
			let opaque = ordered.len() as u32;
			ordered.extend(verts.chunks_exact(3).filter(|t| t[0].flags & 2 != 0).flatten());
			let translucent = ordered.len() as u32 - opaque;
			let bytes = crate::link::bytes_of_slice(&ordered);
			let buffer = unsafe { self.buffer(bytes.len())? };
			unsafe { Self::fill(&buffer, bytes)? };
			if let Some(old) = self.meshes.insert(*k, Mesh { source: verts.clone(), buffer, opaque, translucent }) {
				self.garbage.push(old.buffer);
			}
		}
		let gone: Vec<(i32, i32)> = self.occluders.keys().filter(|k| !scene.occluders.contains_key(*k)).copied().collect();
		for k in gone {
			let o = self.occluders.remove(&k).unwrap();
			self.garbage.push(o.buffer);
		}
		for (k, verts) in &scene.occluders {
			if self.occluders.get(k).is_some_and(|o| Arc::ptr_eq(&o.source, verts)) {
				continue;
			}
			let bytes = crate::link::bytes_of_slice(verts.as_slice());
			let buffer = unsafe { self.buffer(bytes.len())? };
			unsafe { Self::fill(&buffer, bytes)? };
			if let Some(old) = self.occluders.insert(*k, Occluder { source: verts.clone(), buffer, count: verts.len() as u32 }) {
				self.garbage.push(old.buffer);
			}
		}
		Ok(())
	}

	unsafe fn frame(&mut self, queue: &ID3D12CommandQueue, back_buffer: &ID3D12Resource) -> Result<()> {
		unsafe {
			// One frame in flight: what the last one used can be reused or freed now.
			if let Some(v) = self.in_flight.take() {
				self.fence.wait_for_value(v)?;
			}
			self.garbage.clear();

			let desc = back_buffer.GetDesc();
			let (width, height) = (desc.Width as u32, desc.Height);
			crate::hud::set_viewport(width, height);

			let (view, aspect, hud, atlas, entities, textures, avatar, light, game_light) = scene::with(|s| -> Result<_> {
				self.sync_buffers(s)?;
				let avatar = s.avatar.clone().zip(s.avatar_at).map(|(a, at)| (a, at));
				Ok((s.view, s.aspect, s.hud, s.atlas.clone(), s.entities.clone(), s.textures.clone(), avatar, s.light, s.game_light))
			})?;
			// Blocks take their light from the game's picture when this works; never at the cost of drawing.
			let sampling = game_light && !self.frame_failed && match self.ensure_frame_copy(&desc) {
				Ok(ok) => ok,
				Err(e) => {
					log::line(&format!("gpu: cannot copy the game's picture, blocks keep the clock's light: {e}"));
					self.frame_failed = true;
					false
				}
			};

			self.allocator.Reset()?;
			self.list.Reset(&self.allocator, None)?;

			// Textures: the atlas once (and whenever Minecraft sends a new one), the GUI every frame it changes.
			if let Some(a) = atlas.filter(|a| self.atlas.as_ref().is_none_or(|(_, have)| !Arc::ptr_eq(have, a))) {
				let mut t = self.texture(a.width, a.height, 0)?;
				self.upload(&mut t, &a.pixels)?;
				if let Some((old, _)) = self.atlas.replace((t, a)) {
					self.garbage.push(old.resource);
					self.garbage.push(old.upload);
				}
			}
			for (id, tex) in &textures {
				let Some(slot) = texture_slot(*id) else { continue };
				if self.entity_textures.get(id).is_some_and(|(_, have)| Arc::ptr_eq(have, tex)) {
					continue;
				}
				let mut t = self.texture(tex.width, tex.height, slot)?;
				self.upload(&mut t, &tex.pixels)?;
				if let Some((old, _)) = self.entity_textures.insert(*id, (t, tex.clone())) {
					self.garbage.push(old.resource);
					self.garbage.push(old.upload);
				}
			}
			if let Some(link) = LINK.get() {
				if let Some((front, w, h, bottom_up)) = link.acquire_overlay(self.overlay_front) {
					self.overlay_front = front;
					self.overlay_flip = bottom_up;
					if self.overlay.as_ref().is_none_or(|t| t.width != w || t.height != h) {
						let t = self.texture(w, h, 1)?;
						if let Some(old) = self.overlay.replace(t) {
							self.garbage.push(old.resource);
							self.garbage.push(old.upload);
						}
					}
					let mut t = self.overlay.take().unwrap();
					let result = self.upload(&mut t, link.overlay_pixels(front, w, h));
					self.overlay = Some(t);
					result?;
				}
			}

			if sampling {
				// The game's finished picture, before anything of ours is on it.
				let copy = self.frame_copy.as_ref().unwrap();
				let (resource, ready) = (copy.resource.clone(), copy.ready);
				self.barrier(back_buffer, D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_COPY_SOURCE);
				if ready {
					self.barrier(&resource, FRAME_READ, D3D12_RESOURCE_STATE_COPY_DEST);
				}
				self.list.CopyResource(&resource, back_buffer);
				self.barrier(&resource, D3D12_RESOURCE_STATE_COPY_DEST, FRAME_READ);
				self.barrier(back_buffer, D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_RENDER_TARGET);
				self.frame_copy.as_mut().unwrap().ready = true;
			} else {
				self.barrier(back_buffer, D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_RENDER_TARGET);
			}
			let rtv = self.rtv_heap.GetCPUDescriptorHandleForHeapStart();
			self.device.CreateRenderTargetView(back_buffer, None, rtv);
			self.ensure_depth(width, height)?;
			let dsv = self.dsv_heap.GetCPUDescriptorHandleForHeapStart();
			self.list.OMSetRenderTargets(1, Some(&rtv), false, Some(&dsv));
			self.list.ClearDepthStencilView(dsv, D3D12_CLEAR_FLAG_DEPTH, 1.0, 0, None);
			self.list.SetGraphicsRootSignature(&self.root);
			self.list.SetDescriptorHeaps(&[Some(self.srv_heap.clone())]);
			self.list.SetGraphicsRootDescriptorTable(2, self.srv(FRAME_SLOT));
			self.list.IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
			let full = RECT { left: 0, top: 0, right: width as i32, bottom: height as i32 };
			self.list.RSSetScissorRects(&[full]);

			// The camera this frame was rendered with, not the newest one.
			let view = view.and(crate::camera::rendered_view());
			if let (Some(view), true) = (view, hud.shown) {
				let origin = render_origin(&view);
				let (things, selection) = LINK.get().and_then(|l| l.read_world_entities()).unwrap_or_default();
				// The player's model sits at its feet; only drawn when the camera is away from the eye.
				let avatar = avatar.map(|(a, at)| scene::Entities { origin: at, batches: a.batches.clone(), verts: a.verts.clone() });
				let dynamic = self.build_dynamic(&things, selection, entities.as_deref(), avatar.as_ref(), origin)?;
				self.draw_world(view, aspect, width, height, self.srv(0), &dynamic, entities.as_deref(), avatar.as_ref(), light, sampling);
			}

			if let (Some(t), true) = (self.overlay.as_ref().filter(|t| t.ready), hud.shown) {
				self.list.RSSetViewports(&[D3D12_VIEWPORT { TopLeftX: 0.0, TopLeftY: 0.0, Width: width as f32, Height: height as f32, MinDepth: 0.0, MaxDepth: 1.0 }]);
				self.list.SetGraphicsRootDescriptorTable(1, self.srv(1));
				let mut c = Constants::default();
				c.params[0] = if self.overlay_flip { 1.0 } else { 0.0 };
				if let Some((x, y)) = hud.cursor {
					let k = width as f32 / t.width as f32;
					c.right = [x * k, y * k, 1.0, (height as f32 / 720.0).max(1.0)];
				}
				if hud.crosshair && hud.gui_scale > 0 {
					// Around the centre, where Minecraft puts the crosshair (15 GUI pixels) and the
					// attack indicator under it, scaled from the GUI's size to the back buffer's.
					let g = hud.gui_scale as f32 * width as f32 / t.width as f32;
					let (cx, cy) = (width as f32 * 0.5, height as f32 * 0.5);
					c.offset = [cx - 12.0 * g, cy - 12.0 * g, cx + 12.0 * g, cy + 28.0 * g];
				}
				self.set_constants(&c);
				self.list.SetPipelineState(&self.pso_overlay);
				self.list.DrawInstanced(3, 1, 0, 0);
				if c.offset[2] > c.offset[0] {
					self.list.SetPipelineState(&self.pso_invert);
					self.list.DrawInstanced(3, 1, 0, 0);
				}
			}

			self.barrier(back_buffer, D3D12_RESOURCE_STATE_RENDER_TARGET, D3D12_RESOURCE_STATE_PRESENT);
			self.list.Close()?;
			queue.ExecuteCommandLists(&[Some(self.list.cast()?)]);
			let value = self.fence.incr() + 1;
			queue.Signal(self.fence.fence(), value)?;
			self.in_flight = Some(value);
		}
		Ok(())
	}

	/// The shader-visible handle of descriptor slot `slot`.
	fn srv(&self, slot: u32) -> D3D12_GPU_DESCRIPTOR_HANDLE {
		let mut h = unsafe { self.srv_heap.GetGPUDescriptorHandleForHeapStart() };
		h.ptr += (slot * self.srv_step) as u64;
		h
	}

	unsafe fn set_constants(&self, c: &Constants) {
		unsafe { self.list.SetGraphicsRoot32BitConstants(0, CONSTANTS, c as *const Constants as *const _, 0) };
	}

	/// This frame's entity geometry in one vertex buffer: dropped items, arrows and cracks (built
	/// here, relative to the render origin), then Minecraft's scene (relative to its own origin).
	unsafe fn build_dynamic(
		&mut self,
		things: &[WorldEntity],
		selection: Option<[f32; 6]>,
		entities: Option<&scene::Entities>,
		avatar: Option<&scene::Entities>,
		origin: [f64; 3],
	) -> Result<Dynamic> {
		let mut solid = Vec::new();
		let mut see_through = Vec::new();
		let mut shadows = Vec::new();
		for t in things {
			let at = [(t.pos[0] as f64 - origin[0]) as f32, (t.pos[1] as f64 - origin[1]) as f32, (t.pos[2] as f64 - origin[2]) as f32];
			match t.kind {
				proto::WE_ITEM => item_sprite(&mut solid, at, t),
				proto::WE_BLOCK => item_block(&mut solid, at, t),
				proto::WE_ARROW | proto::WE_TRIDENT => arrow(&mut solid, at, t),
				proto::WE_CRACK => crack(&mut see_through, at, t),
				proto::WE_SHADOW => shadow(&mut shadows, at, t),
				_ => {}
			}
		}
		let lines = selection.map(|b| outline(b, origin)).unwrap_or_default();
		let mut verts = solid;
		let solid_count = verts.len() as u32;
		verts.extend(see_through);
		let see_through_count = verts.len() as u32 - solid_count;
		let shadows_first = verts.len() as u32;
		verts.extend(shadows);
		let shadow_count = verts.len() as u32 - shadows_first;
		let lines_first = verts.len() as u32;
		verts.extend(lines);
		let line_count = verts.len() as u32 - lines_first;
		let scene_first = verts.len() as u32;
		if let Some(e) = entities {
			verts.extend_from_slice(&e.verts);
		}
		let avatar_first = verts.len() as u32;
		if let Some(a) = avatar {
			verts.extend_from_slice(&a.verts);
		}
		let mut d = Dynamic { solid: solid_count, see_through: see_through_count, shadows_first, shadows: shadow_count, lines_first, lines: line_count, scene_first, avatar_first, total: verts.len() as u32 };
		if verts.is_empty() {
			d.total = 0;
			return Ok(d);
		}
		if self.dynamic.as_ref().is_none_or(|(_, cap)| *cap < verts.len()) {
			let cap = verts.len().next_power_of_two().max(4096);
			let buffer = unsafe { self.buffer(cap * size_of::<RenVertex>())? };
			if let Some((old, _)) = self.dynamic.replace((buffer, cap)) {
				self.garbage.push(old);
			}
		}
		unsafe { Self::fill(&self.dynamic.as_ref().unwrap().0, crate::link::bytes_of_slice(&verts))? };
		Ok(d)
	}

	/// Collision into depth, then Minecraft's blocks and entities, all relative to a point near
	/// the eye so float precision holds anywhere in the world.
	#[allow(clippy::too_many_arguments)]
	unsafe fn draw_world(
		&self,
		view: scene::View,
		aspect: f32,
		width: u32,
		height: u32,
		atlas: D3D12_GPU_DESCRIPTOR_HANDLE,
		dynamic: &Dynamic,
		entities: Option<&scene::Entities>,
		avatar: Option<&scene::Entities>,
		light: crate::lighting::Light,
		sampling: bool,
	) {
		if !self.atlas.as_ref().is_some_and(|(t, _)| t.ready) {
			return;
		}
		unsafe {
			// The game letterboxes when the screen is wider (or taller) than its camera's aspect.
			let screen = width as f32 / height as f32;
			let aspect = if aspect > 0.1 { aspect } else { screen };
			let (vw, vh) = if aspect < screen { (height as f32 * aspect, height as f32) } else { (width as f32, width as f32 / aspect) };
			self.list.RSSetViewports(&[D3D12_VIEWPORT {
				TopLeftX: (width as f32 - vw) * 0.5,
				TopLeftY: (height as f32 - vh) * 0.5,
				Width: vw,
				Height: vh,
				MinDepth: 0.0,
				MaxDepth: 1.0,
			}]);

			let origin = render_origin(&view);
			let (yaw, pitch) = (view.yaw.to_radians(), view.pitch.clamp(-89.99, 89.99).to_radians());
			let f = [-yaw.sin() * pitch.cos(), -pitch.sin(), yaw.cos() * pitch.cos()];
			let r = norm(cross(f, [0.0, 1.0, 0.0]));
			let u = cross(r, f);
			let sy = 1.0 / (view.fov_deg.to_radians() * 0.5).tan();
			let mut c = Constants {
				right: [r[0], r[1], r[2], 0.0],
				up: [u[0], u[1], u[2], 0.0],
				fwd: [f[0], f[1], f[2], 0.0],
				eye: [(view.eye[0] - origin[0]) as f32, (view.eye[1] - origin[1]) as f32, (view.eye[2] - origin[2]) as f32, 0.0],
				proj: [sy / aspect, sy, FAR / (FAR - NEAR), -NEAR * FAR / (FAR - NEAR)],
				offset: [0.0; 4],
				params: [0.08, 1.0, 0.0, 0.0],
				sun_dir: [light.dir[0], light.dir[1], light.dir[2], 0.0],
				sun_col: [light.direct[0], light.direct[1], light.direct[2], 0.0],
				ambient: [light.ambient[0], light.ambient[1], light.ambient[2], 0.0],
				// Where the game's picture is on screen, and how its brightness becomes light: 0.42
				// is about the average of a sunlit scene, and the radius is wide enough to smooth texture detail.
				frame: [(width as f32 - vw) * 0.5, (height as f32 - vh) * 0.5, vw, vh],
				look: [if sampling { 1.0 } else { 0.0 }, 0.42, 0.5, vh * 0.12],
			};
			let near = |p: [f64; 3], reach: f64| (p[0] - view.eye[0]).abs() < reach && (p[1] - view.eye[1]).abs() < reach && (p[2] - view.eye[2]).abs() < reach;

			// The scanned collision, depth only.
			self.list.SetPipelineState(&self.pso_occluder);
			for ((rx, rz), o) in &self.occluders {
				let at = [(*rx * 8) as f64, 0.0, (*rz * 8) as f64];
				if !near([at[0] + 4.0, view.eye[1], at[2] + 4.0], OCCLUDE_DISTANCE) || o.count == 0 {
					continue;
				}
				c.offset = [(at[0] - origin[0]) as f32, (at[1] - origin[1]) as f32, (at[2] - origin[2]) as f32, 0.0];
				self.set_constants(&c);
				self.list.IASetVertexBuffers(0, Some(&[D3D12_VERTEX_BUFFER_VIEW { BufferLocation: o.buffer.GetGPUVirtualAddress(), SizeInBytes: o.count * 12, StrideInBytes: 12 }]));
				self.list.DrawInstanced(o.count, 1, 0, 0);
			}

			// Minecraft's blocks: opaque and cutout, then translucent.
			self.list.SetGraphicsRootDescriptorTable(1, atlas);
			for (pass, pso) in [(0, &self.pso_opaque), (1, &self.pso_translucent)] {
				self.list.SetPipelineState(pso);
				for (s, m) in &self.meshes {
					let at = [(s[0] * 16) as f64, (s[1] * 16) as f64, (s[2] * 16) as f64];
					let (first, count) = if pass == 0 { (0, m.opaque) } else { (m.opaque, m.translucent) };
					if count == 0 || !near([at[0] + 8.0, at[1] + 8.0, at[2] + 8.0], DRAW_DISTANCE) {
						continue;
					}
					c.offset = [(at[0] - origin[0]) as f32, (at[1] - origin[1]) as f32, (at[2] - origin[2]) as f32, 0.0];
					self.set_constants(&c);
					let total = m.opaque + m.translucent;
					self.list.IASetVertexBuffers(0, Some(&[D3D12_VERTEX_BUFFER_VIEW { BufferLocation: m.buffer.GetGPUVirtualAddress(), SizeInBytes: total * 32, StrideInBytes: 32 }]));
					self.list.DrawInstanced(count, 1, first, 0);
				}
				self.draw_entities(&mut c, pass, origin, dynamic, entities, dynamic.scene_first, atlas);
				self.draw_entities(&mut c, pass, origin, dynamic, avatar, dynamic.avatar_first, atlas);
				if pass == 1 {
					self.draw_extras(&mut c, dynamic, atlas);
				}
			}
		}
	}

	/// Contact shadows and the targeted block's outline (both built relative to the origin).
	unsafe fn draw_extras(&self, c: &mut Constants, d: &Dynamic, atlas: D3D12_GPU_DESCRIPTOR_HANDLE) {
		let Some((buffer, _)) = self.dynamic.as_ref().filter(|_| d.total > 0) else { return };
		unsafe {
			self.list.IASetVertexBuffers(0, Some(&[D3D12_VERTEX_BUFFER_VIEW { BufferLocation: buffer.GetGPUVirtualAddress(), SizeInBytes: d.total * 32, StrideInBytes: 32 }]));
			self.list.SetGraphicsRootDescriptorTable(1, atlas);
			c.offset = [0.0; 4];
			self.set_constants(c);
			if d.shadows > 0 {
				self.list.SetPipelineState(&self.pso_shadow);
				self.list.DrawInstanced(d.shadows, 1, d.shadows_first, 0);
			}
			if d.lines > 0 {
				self.list.SetPipelineState(&self.pso_lines);
				self.list.IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_LINELIST);
				self.list.DrawInstanced(d.lines, 1, d.lines_first, 0);
				self.list.IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
			}
			self.list.SetPipelineState(&self.pso_translucent);
		}
	}

	/// One pass's share of this frame's entities: built things (atlas), then Minecraft's batches,
	/// each with its own texture.
	#[allow(clippy::too_many_arguments)]
	unsafe fn draw_entities(
		&self,
		c: &mut Constants,
		pass: u32,
		origin: [f64; 3],
		d: &Dynamic,
		entities: Option<&scene::Entities>,
		base: u32,
		atlas: D3D12_GPU_DESCRIPTOR_HANDLE,
	) {
		let Some((buffer, _)) = self.dynamic.as_ref().filter(|_| d.total > 0) else { return };
		unsafe {
			self.list.IASetVertexBuffers(0, Some(&[D3D12_VERTEX_BUFFER_VIEW { BufferLocation: buffer.GetGPUVirtualAddress(), SizeInBytes: d.total * 32, StrideInBytes: 32 }]));
			self.list.SetGraphicsRootDescriptorTable(1, atlas);
			if base == d.scene_first {
				// The built things (items, arrows, cracks) go with the scene.
				c.offset = [0.0; 4];
				self.set_constants(c);
				let (first, count) = if pass == 0 { (0, d.solid) } else { (d.solid, d.see_through) };
				if count > 0 {
					self.list.DrawInstanced(count, 1, first, 0);
				}
			}
			let Some(e) = entities else { return };
			c.offset = [(e.origin[0] - origin[0]) as f32, (e.origin[1] - origin[1]) as f32, (e.origin[2] - origin[2]) as f32, 0.0];
			self.set_constants(c);
			for b in e.batches.iter().filter(|b| b.translucent == (pass == 1)) {
				let table = if b.texture == 0 {
					atlas
				} else {
					match (texture_slot(b.texture), self.entity_textures.get(&b.texture)) {
						(Some(slot), Some((t, _))) if t.ready => self.srv(slot),
						_ => continue,
					}
				};
				self.list.SetGraphicsRootDescriptorTable(1, table);
				self.list.DrawInstanced(b.count, 1, base + b.first, 0);
			}
			self.list.SetGraphicsRootDescriptorTable(1, atlas);
		}
	}
}

struct Dynamic {
	solid: u32,
	see_through: u32,
	shadows_first: u32,
	shadows: u32,
	lines_first: u32,
	lines: u32,
	scene_first: u32,
	avatar_first: u32,
	total: u32,
}

fn render_origin(view: &scene::View) -> [f64; 3] {
	view.eye.map(|v| (v / 16.0).floor() * 16.0)
}

/// Full daylight, no block light: entities are lit like the open air around them.
const LIT: u32 = 15 << 8;

fn vert(p: [f32; 3], u: f32, v: f32, color: u32, flags: u32) -> RenVertex {
	RenVertex { pos: p, u, v, color, light: LIT, flags }
}

/// Two triangles a-b-c, a-c-d with the rect's corners (u0,v0) (u1,v0) (u1,v1) (u0,v1).
fn quad(out: &mut Vec<RenVertex>, p: [[f32; 3]; 4], uv: [f32; 4], color: u32, flags: u32) {
	let t = [(uv[0], uv[1]), (uv[2], uv[1]), (uv[2], uv[3]), (uv[0], uv[3])];
	for i in [0, 1, 2, 0, 2, 3] {
		out.push(vert(p[i], t[i].0, t[i].1, color, flags));
	}
}

fn add(a: [f32; 3], b: [f32; 3], k: f32) -> [f32; 3] {
	[a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k]
}

const WHITE: u32 = 0xFFFF_FFFF;
const CUTOUT: u32 = 1;
const TRANSLUCENT: u32 = 2;

/// A dropped item: its icon, upright, turning about the vertical.
fn item_sprite(out: &mut Vec<RenVertex>, at: [f32; 3], t: &WorldEntity) {
	let y = t.yaw.to_radians();
	let r = [y.cos(), 0.0, y.sin()];
	let h = t.scale * 0.5;
	let up = [0.0, 1.0, 0.0];
	let p = [add(add(at, r, -h), up, h), add(add(at, r, h), up, h), add(add(at, r, h), up, -h), add(add(at, r, -h), up, -h)];
	quad(out, p, t.uv[0], WHITE, CUTOUT);
}

/// A dropped block: a small cube turning about the vertical, sides / top / bottom textured.
fn item_block(out: &mut Vec<RenVertex>, at: [f32; 3], t: &WorldEntity) {
	let (sin, cos) = t.yaw.to_radians().sin_cos();
	let h = t.scale * 0.5;
	let corner = |x: f32, y: f32, z: f32| [at[0] + (x * cos - z * sin) * h, at[1] + y * h, at[2] + (x * sin + z * cos) * h];
	let top = if t.tint != 0 { t.tint } else { WHITE };
	let face = |dir: u32| CUTOUT | (dir << 4);
	quad(out, [corner(-1.0, 1.0, -1.0), corner(1.0, 1.0, -1.0), corner(1.0, 1.0, 1.0), corner(-1.0, 1.0, 1.0)], t.uv[1], top, face(2));
	quad(out, [corner(-1.0, -1.0, 1.0), corner(1.0, -1.0, 1.0), corner(1.0, -1.0, -1.0), corner(-1.0, -1.0, -1.0)], t.uv[2], WHITE, face(1));
	quad(out, [corner(1.0, 1.0, -1.0), corner(-1.0, 1.0, -1.0), corner(-1.0, -1.0, -1.0), corner(1.0, -1.0, -1.0)], t.uv[0], WHITE, face(3));
	quad(out, [corner(-1.0, 1.0, 1.0), corner(1.0, 1.0, 1.0), corner(1.0, -1.0, 1.0), corner(-1.0, -1.0, 1.0)], t.uv[0], WHITE, face(4));
	quad(out, [corner(-1.0, 1.0, -1.0), corner(-1.0, 1.0, 1.0), corner(-1.0, -1.0, 1.0), corner(-1.0, -1.0, -1.0)], t.uv[0], WHITE, face(5));
	quad(out, [corner(1.0, 1.0, 1.0), corner(1.0, 1.0, -1.0), corner(1.0, -1.0, -1.0), corner(1.0, -1.0, 1.0)], t.uv[0], WHITE, face(6));
}

/// An arrow or trident in flight or stuck: its icon on two crossed quads along its direction.
fn arrow(out: &mut Vec<RenVertex>, at: [f32; 3], t: &WorldEntity) {
	let (yaw, pitch) = (t.yaw.to_radians(), t.pitch.to_radians());
	let d = [-yaw.sin() * pitch.cos(), -pitch.sin(), yaw.cos() * pitch.cos()];
	let side = [yaw.cos(), 0.0, yaw.sin()];
	let up = [d[1] * side[2] - d[2] * side[1], d[2] * side[0] - d[0] * side[2], d[0] * side[1] - d[1] * side[0]];
	let half = 0.35 * t.scale;
	for n in [side, up] {
		// The icon's diagonal runs from its bottom-left (tail) to its top-right (tip).
		let p = [add(add(at, d, -half), n, half), add(add(at, d, half), n, half), add(add(at, d, half), n, -half), add(add(at, d, -half), n, -half)];
		let uv = t.uv[0];
		out.extend([(p[0], uv[0], uv[1]), (p[1], uv[2], uv[1]), (p[2], uv[2], uv[3]), (p[0], uv[0], uv[1]), (p[2], uv[2], uv[3]), (p[3], uv[0], uv[3])].map(|(p, u, v)| vert(p, u, v, WHITE, CUTOUT)));
	}
}

/// A soft dark disc on the ground under a player or mob, `scale` across.
fn shadow(out: &mut Vec<RenVertex>, at: [f32; 3], t: &WorldEntity) {
	let h = t.scale.max(0.3) * 0.7;
	let y = at[1] + 0.02;
	let p = [[at[0] - h, y, at[2] - h], [at[0] + h, y, at[2] - h], [at[0] + h, y, at[2] + h], [at[0] - h, y, at[2] + h]];
	quad(out, p, [0.0, 0.0, 1.0, 1.0], WHITE, 0);
}

/// The 12 edges of the targeted block's box (Minecraft coords), relative to `origin`, as lines.
fn outline(b: [f32; 6], origin: [f64; 3]) -> Vec<RenVertex> {
	let grow = 0.002;
	let lo = [0, 1, 2].map(|k| (b[k] as f64 - grow - origin[k]) as f32);
	let hi = [0, 1, 2].map(|k| (b[k + 3] as f64 + grow - origin[k]) as f32);
	let c = |i: usize| [if i & 1 != 0 { hi[0] } else { lo[0] }, if i & 2 != 0 { hi[1] } else { lo[1] }, if i & 4 != 0 { hi[2] } else { lo[2] }];
	let mut out = Vec::new();
	for i in 0..8usize {
		for bit in [1usize, 2, 4] {
			if i & bit == 0 {
				out.push(vert(c(i), 0.0, 0.0, WHITE, 0));
				out.push(vert(c(i | bit), 0.0, 0.0, WHITE, 0));
			}
		}
	}
	out
}

/// Block-breaking cracks over the box from `at` (its minimum corner), `ext` in size.
fn crack(out: &mut Vec<RenVertex>, at: [f32; 3], t: &WorldEntity) {
	let [x0, y0, z0] = at;
	let [x1, y1, z1] = [at[0] + t.ext[0], at[1] + t.ext[1], at[2] + t.ext[2]];
	let f = TRANSLUCENT;
	let uv = t.uv[0];
	quad(out, [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], uv, WHITE, f | (2 << 4));
	quad(out, [[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]], uv, WHITE, f | (1 << 4));
	quad(out, [[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]], uv, WHITE, f | (3 << 4));
	quad(out, [[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]], uv, WHITE, f | (4 << 4));
	quad(out, [[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]], uv, WHITE, f | (5 << 4));
	quad(out, [[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]], uv, WHITE, f | (6 << 4));
}

fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
	[a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}

fn norm(a: [f32; 3]) -> [f32; 3] {
	let l = (a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).sqrt().max(1e-9);
	[a[0] / l, a[1] / l, a[2] / l]
}
