#!/usr/bin/env python3
"""Build the shared iOS/Android firmware; never flash a device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent
FQBNS = {
    '175': 'esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=opi,CDCOnBoot=cdc',
    '175c': 'esp32:esp32:esp32s3:FlashSize=32M,PartitionScheme=app5M_fat24M_32MB,PSRAM=opi,CDCOnBoot=cdc',
}


def package(output, dest, version, board, ota_private_key=None, diagnostic=False):
    app = (output / 'MotoNav.ino.bin').read_bytes()
    merged = (output / 'MotoNav.ino.merged.bin').read_bytes()
    if not app or app[0] != 0xE9 or merged[0x10000:0x10000 + len(app)] != app:
        raise ValueError('Invalid ESP32 image or unexpected application offset')
    dest.mkdir(parents=True, exist_ok=True)
    diagnostic_suffix = '-imu-diagnostic' if diagnostic else ''
    label = f'MotoNav_universal_{"1.75C" if board == "175c" else "1.75"}_v{version}_panel0{diagnostic_suffix}'
    entries = []
    for suffix, data in [('app_0x10000', app), ('full_0x0', merged[:0x10000 + len(app)])]:
        name = f'{label}_{suffix}.bin'
        (dest / name).write_bytes(data)
        entries.append(dict(file=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    manifest = dict(firmware=version, platform="universal",
                    board=('Waveshare ESP32-S3-Touch-AMOLED-1.75C' if board == '175c'
                           else 'Waveshare ESP32-S3-Touch-AMOLED-1.75'),
                    esp32Core='3.3.10',
                    flashSize=('32MB' if board == '175c' else '16MB'),
                    partition=('app5M_fat24M_32MB' if board == '175c' else 'app3M_fat9M_16MB'),
                    verifiedApplicationOffset='0x10000', deviceTested=False,
                    diagnostic=diagnostic, files=entries)
    manifest_path = dest / f'{label}_manifest.json'
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    if ota_private_key:
        subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(ROOT / 'sign-ota.ps1'),
                        '-ManifestPath', str(manifest_path), '-PrivateKeyPath', ota_private_key], check=True)
        subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(ROOT / 'verify-ota.ps1'),
                        '-ManifestPath', str(manifest_path)], check=True)
    return dest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli', default='arduino-cli')
    parser.add_argument('--config-file', default='')
    parser.add_argument('--board', choices=sorted(FQBNS), default='175')
    parser.add_argument('--jobs', type=int, default=4)
    parser.add_argument('--imu-diagnostics', action='store_true',
                        help='enable opt-in serial-only QMI8658 probe and sample logging')
    parser.add_argument('--ota-private-key', default='')
    args = parser.parse_args()
    if not 1 <= args.jobs <= 32:
        parser.error('--jobs must be between 1 and 32')
    source = ROOT / 'boards/175/MotoNav'
    version = re.search(r'^#define FW_VERSION "v([^\"]+)"', (source / 'src/core/FirmwareConfig.h').read_text(), re.MULTILINE).group(1)
    build = ROOT / '.build' / 'universal' / args.board
    output = build / 'output'
    output.mkdir(parents=True, exist_ok=True)
    prefix_map = '-ffile-prefix-map=' + str(Path.home()) + '=/builder'
    command = [args.cli]
    if args.config_file:
        command += ['--config-file', args.config_file]
    command += ['compile', '--jobs', str(args.jobs), '--libraries', str(ROOT / 'libraries'),
                '--build-path', str(build / 'build'), '--fqbn', FQBNS[args.board],
                '--build-property', 'compiler.c.extra_flags=' + prefix_map]
    cpp_flags = prefix_map
    if args.board == '175c':
        cpp_flags += ' -DMOTONAV_175C=1'
    if args.imu_diagnostics:
        cpp_flags += ' -DMOTONAV_IMU_DIAGNOSTICS=1'
    command += ['--build-property', 'compiler.cpp.extra_flags=' + cpp_flags,
                '--output-dir', str(output), str(source)]
    subprocess.run(command, check=True)
    print(package(output, ROOT / 'dist' / args.board / 'panel0', version, args.board,
                  args.ota_private_key or None, args.imu_diagnostics))


if __name__ == '__main__':
    main()
