# Multi-dex keep file for YuNian One-Piece Shell
# Forces shell/security classes into the main DEX (classes.dex).
# All other classes (business, UI, features) go to secondary DEX files.
# Those secondary DEX files are then encrypted as shell payload.

# Shell Application entry point
-keep class com.yunian.ai.security.YuNianShellApplication { *; }
-keep class com.yunian.ai.security.OnePieceShellGate { *; }

# Security gates (G0 facade, CompositeVmpRuntime)
-keep class com.yunian.ai.security.G0 { *; }
-keep class com.yunian.ai.security.CompositeVmpRuntime { *; }

# DEX fragment loader
-keep class com.yunian.ai.security.DexFragmentLoader { *; }

# KMS (native crypto)
-keep class com.yunian.ai.security.KmsProvider { *; }
-keep class com.yunian.ai.security.NativeBridge { *; }

# Security state and guard
-keep class com.yunian.ai.security.SecurityState { *; }
-keep class com.yunian.ai.security.SecurityGuard { *; }

# Manifest integrity (AEAD)
-keep class com.yunian.ai.security.TinkAeadProvider { *; }

# Attestation / audit
-keep class com.yunian.ai.security.HardwareKeyAttestor { *; }
-keep class com.yunian.ai.security.AttestationDataParser { *; }
-keep class com.yunian.ai.security.SecureStrings { *; }
