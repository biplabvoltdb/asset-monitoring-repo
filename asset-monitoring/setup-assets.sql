-- Setup initial asset hierarchy for PoC workload
-- 3000 total sensors: 500 batched + 2500 real-time

-- Create root fleet
EXEC UpsertAsset 'fleet-001', 'Industrial Fleet Alpha', 'Fleet', NULL, '{"description":"Primary industrial fleet"}';

-- Create 30 machines (100 sensors each = 3000 total)
EXEC UpsertAsset 'machine-001', 'Compressor Unit 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-002', 'Compressor Unit 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-003', 'Compressor Unit 003', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-004', 'Compressor Unit 004', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-005', 'Compressor Unit 005', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-006', 'Turbine Unit 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-007', 'Turbine Unit 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-008', 'Turbine Unit 003', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-009', 'Pump Unit 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-010', 'Pump Unit 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-011', 'Generator Unit 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-012', 'Generator Unit 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-013', 'HVAC Unit 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-014', 'HVAC Unit 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-015', 'Cooling Tower 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-016', 'Cooling Tower 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-017', 'Boiler Unit 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-018', 'Boiler Unit 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-019', 'Conveyor System 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-020', 'Conveyor System 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-021', 'Mixer Unit 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-022', 'Mixer Unit 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-023', 'Valve Control 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-024', 'Valve Control 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-025', 'Heat Exchanger 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-026', 'Heat Exchanger 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-027', 'Tank System 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-028', 'Tank System 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-029', 'Filtration Unit 001', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';
EXEC UpsertAsset 'machine-030', 'Filtration Unit 002', 'Machine', 'fleet-001', '{"tempThreshold":50,"pressureThreshold":30}';

-- Verify assets created
SELECT COUNT(*) as total_assets FROM ASSET;
