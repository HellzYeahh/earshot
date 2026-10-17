package dev.earshot.protocol.qcultra2

/*
 * GENERATED from docs/bmap-tables/functions-bose-app-13.3.4.json (the Bose app 13.3.4 enums
 * BmapFunctionBlock / BmapFunction; names only, no Bose code). Do not edit by hand: regenerate.
 * Hand-made additions (unnamed ids seen in the fw 10.12 scan, blocks 23-26) live in FunctionNames.
 */

internal object BoseAppFunctionTable {
    val blocks: Map<Int, String> = mapOf(
        0 to "ProductInfo",
        1 to "Settings",
        2 to "Status",
        3 to "FirmwareUpdate",
        4 to "DeviceManagement",
        5 to "AudioManagement",
        6 to "CallManagement",
        7 to "Control",
        8 to "Debug",
        9 to "Notification",
        10 to "ReservedBosebuild1",
        11 to "ReservedBosebuild2",
        12 to "HearingAssistance",
        13 to "DataCollection",
        14 to "HeartRate",
        15 to "PeerBud",
        16 to "Vpa",
        17 to "Wifi",
        18 to "Authentication",
        19 to "Experimental",
        20 to "Cloud",
        21 to "AugmentedReality",
        22 to "Print",
        31 to "AudioModes",
    )

    /** block id -> (function id -> name). */
    val functions: Map<Int, Map<Int, String>> = mapOf(
        0 to mapOf(
            0 to "FblockInfo", 1 to "BmapVersion", 2 to "AllFblocks", 3 to "ProductIdVariants",
            4 to "GetAllFunctions", 5 to "FirmwareVersion", 6 to "MacAddress", 7 to "SerialNumber",
            8 to "DateOfManufacture", 9 to "BoardSerialNumber", 10 to "HardwareRevision",
            11 to "ComponentDevices", 12 to "Guid", 13 to "ProductSalesRegion", 14 to "ProductMarking",
            15 to "OriginalProductName", 16 to "UnifiedCommunicationsConfig", 17 to "ComponentId",
            18 to "EarbudMode", 19 to "PeerId", 20 to "MacAddressForRole", 21 to "MacAddressForComponent",
            22 to "BondedGroupSupport", 23 to "LeAudioSupported",
        ),
        1 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "ProductName", 3 to "VoicePrompts", 4 to "StandbyTimer",
            5 to "Cnc", 6 to "Anr", 7 to "RangeControl", 8 to "Alerts", 9 to "Buttons", 10 to "Multipoint",
            11 to "Sidetone", 12 to "SetupComplete", 13 to "ConversationMode", 14 to "CncPersistence",
            15 to "CncPresets", 16 to "OnHeadDetection", 19 to "LedBrightness",
            20 to "MotionInactivityAutoOff", 22 to "FlipToOff", 24 to "AutoPlayPause",
            26 to "TeamsButtonMode", 27 to "AutoAnswer", 28 to "VolumeControl", 29 to "AutoAwareMode",
            30 to "SourceBargeIn", 32 to "AutoVolumeLevel", 33 to "Grouping", 34 to "DisableCaptouch",
            37 to "LeAudioControl", 38 to "LeAudioBroadcastConfig", 39 to "HeadGesture", 41 to "LeaGrouping",
            47 to "LeaGroupMember",
        ),
        2 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "BatteryLevel", 3 to "AuxCableDetection",
            4 to "MicrophoneLevel", 5 to "ChargerDetect", 6 to "Preset", 7 to "NetworkUp", 8 to "CrashData",
            9 to "InEar", 10 to "ChargingCaseLid", 11 to "UnifiedCommunicationsLink", 12 to "Button",
            13 to "BatteryLog", 14 to "BatteryLogRaw", 15 to "BatteryCondition", 16 to "OperationMode",
            17 to "BatteryCapacity", 18 to "ProcessorTemperature", 19 to "MoistureDetection",
            20 to "Monotime", 21 to "MicMute",
        ),
        3 to mapOf(
            0 to "FunctionBlockInfo", 1 to "State", 2 to "Init", 3 to "DataTransfer", 4 to "Synchronize",
            5 to "Validate", 6 to "Run", 7 to "Reset", 8 to "DataTransferWithSequenceNumber",
            9 to "DataTransferCrc", 10 to "SubComponentProgress", 11 to "Commit", 12 to "Abort",
            13 to "Count", 14 to "Log", 15 to "UpdateMode", 16 to "DataTransferProgress",
        ),
        4 to mapOf(
            0 to "FblockInfo", 1 to "Connect", 2 to "Disconnect", 3 to "RemoveDevice", 4 to "ListDevices",
            5 to "Info", 6 to "ExtendedInfo", 7 to "ClearDeviceList", 8 to "PairingMode", 9 to "AppAddress",
            10 to "PrepareP2p", 11 to "P2pMode", 12 to "Routing", 13 to "P2pFeatures", 14 to "Features",
            15 to "BoseProduct", 16 to "ConnectionPriority", 17 to "UserCarouselSelect",
            18 to "AvailableToConnect", 19 to "LeAudioCheck",
        ),
        5 to mapOf(
            0 to "FblockInfo", 1 to "Source", 2 to "GetAll", 3 to "Control", 4 to "Status", 5 to "Volume",
            6 to "NowPlaying", 7 to "Latency", 8 to "ChannelParameter", 9 to "SignalClip",
            10 to "ChannelCount", 11 to "ToneMatchPreset", 14 to "ChannelParameterCapabilities",
            15 to "SpatialAudioMode", 16 to "SpatialAudioStatus", 17 to "SpatialAudioCalibration",
        ),
        7 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "Chirp", 3 to "BmapRole", 4 to "Power",
            5 to "FactoryDefault", 6 to "ShipMode", 7 to "ANROnly", 8 to "MaxSourceCurrent",
            9 to "ChargingCaseLidStatus", 10 to "ClientInteraction", 11 to "Reset",
            12 to "BatteryRecoveryPending", 13 to "PrepareToPowerOff", 14 to "SecondaryProcessorTest",
            15 to "FitQuality",
        ),
        9 to mapOf(
            0 to "FblockInfo", 1 to "Reset", 2 to "ByFblock", 3 to "ByFunction", 4 to "Periodic",
        ),
        12 to mapOf(
            0 to "FblockInfo", 1 to "WdrcCompleteUpdate", 2 to "WdrcIndividualUpdates", 3 to "Directionality",
            4 to "DirectionalitySettings", 5 to "NoiseReductionControl", 6 to "NoiseReductionSettings",
            7 to "MappedSettings", 8 to "WdrcBandDefinitions", 9 to "EqBandDefinitions",
            10 to "SubprocessorVersion", 11 to "Muting", 12 to "BoostEq", 13 to "Limits",
            14 to "SubprocessorStatus", 15 to "MappedSettingsMode", 16 to "MappedSettingsOffsetMode",
            17 to "GlobalMute", 18 to "AlgorithmControl", 19 to "LiveMetrics", 20 to "DoffAutoOffTime",
            21 to "PresetManagement",
        ),
        13 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "Records", 5 to "Uid", 6 to "Enable", 7 to "OneRecord",
            8 to "Confirmation", 9 to "CrashLog", 10 to "DeleteCrashLog", 11 to "DeleteAllRecords",
            12 to "CrashLogWithDelay",
        ),
        14 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "HeartRate", 3 to "HeartRateStats",
            4 to "SpeedAndDistance", 5 to "StepRateStats", 6 to "Calories", 7 to "Vo2", 8 to "UserInfo",
            9 to "WorkoutInfo", 10 to "HeartRateReadingReliability", 11 to "OpticalSensorStatus",
            12 to "PostStatus", 13 to "CalibrationInfo", 14 to "FirmwareVersion", 15 to "HardwareInfo",
        ),
        16 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "SupportedVpas", 3 to "Wuw", 4 to "Language",
            5 to "Trigger",
        ),
        17 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "AvailableNetworks", 3 to "ConfigureNetwork",
            4 to "GetSecurityType", 5 to "RemoveNetwork", 6 to "EncryptedConfigureNetwork",
            7 to "SupportedSecurityTypes", 8 to "SoftAPConnection", 9 to "SupportedFrequencies",
        ),
        18 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "RotatingPublicKey", 3 to "Challenge",
            4 to "EcdhePublicKey", 5 to "GenerateChallenge", 6 to "VerifyChallengeResponse",
            7 to "ProductIrk", 8 to "CondensedChallenge", 9 to "DeviceIdentityPublicKey", 10 to "FeatureLock",
            11 to "OtpKeyType", 12 to "ProductName", 13 to "PlatformName", 14 to "AppIrk",
            15 to "GenerateActivationChallenge", 16 to "VerifyActivationChallenge", 17 to "Activation",
            18 to "CondensedCloudChallenge", 19 to "ValidatedDeviceIdentityKeypair", 20 to "Reset",
            21 to "ExtendedChallenge", 22 to "GenerateFastActivationChallenge",
            23 to "VerifyFastActivationChallengeResponse", 24 to "UnencryptedProductIrk",
            25 to "PropagateProductIrk", 26 to "NoTokenChallenge", 27 to "ProductToCloudChallenge",
            28 to "ProductToCloudChallengeVerifyReponse", 29 to "CloudToProductChallenge", 30 to "Ciphers",
            31 to "PublicKeyWithCipher", 32 to "GoogleFeatureKeys", 33 to "GoogleFeatureKeyData",
        ),
        20 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "Activate", 3 to "Synchronize", 4 to "UpdateState",
            5 to "InstallUpdate", 6 to "UpdateProgress",
        ),
        21 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "Status",
        ),
        22 to mapOf(
            0 to "FBlockInfo", 1 to "GetAll", 2 to "Output", 3 to "Bind", 4 to "Unbind", 5 to "Verbosity",
            6 to "Transports", 7 to "Clients",
        ),
        31 to mapOf(
            0 to "FblockInfo", 1 to "GetAll", 2 to "Capabilities", 3 to "CurrentMode", 4 to "DefaultMode",
            5 to "Persistence", 6 to "ModeConfig", 7 to "UserIndices", 8 to "Favorites", 9 to "Reset",
            10 to "SettingsConfig", 11 to "NamesSupported",
        ),
    )
}
