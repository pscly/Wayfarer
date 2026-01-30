from __future__ import annotations

"""Stdlib-only geohash encode/decode.

We keep this tiny and dependency-free; precision=5 (approx ~5km) is the
project default for weather caching.
"""

_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
_DECODE_MAP = {c: i for i, c in enumerate(_BASE32)}


def encode(latitude: float, longitude: float, *, precision: int = 5) -> str:
    if precision <= 0:
        raise ValueError("precision must be > 0")

    lat_min, lat_max = -90.0, 90.0
    lon_min, lon_max = -180.0, 180.0

    bits = [16, 8, 4, 2, 1]
    bit = 0
    ch = 0
    even = True
    out: list[str] = []

    while len(out) < precision:
        if even:
            mid = (lon_min + lon_max) / 2.0
            if longitude >= mid:
                ch |= bits[bit]
                lon_min = mid
            else:
                lon_max = mid
        else:
            mid = (lat_min + lat_max) / 2.0
            if latitude >= mid:
                ch |= bits[bit]
                lat_min = mid
            else:
                lat_max = mid

        even = not even
        if bit < 4:
            bit += 1
            continue

        out.append(_BASE32[ch])
        bit = 0
        ch = 0

    return "".join(out)


def decode_bbox(geohash: str) -> tuple[float, float, float, float]:
    """Return (lat_min, lat_max, lon_min, lon_max) for geohash."""

    if not geohash:
        raise ValueError("geohash must be non-empty")

    lat_min, lat_max = -90.0, 90.0
    lon_min, lon_max = -180.0, 180.0
    even = True

    for c in geohash.lower():
        try:
            cd = _DECODE_MAP[c]
        except KeyError as e:
            raise ValueError(f"Invalid geohash character: {c!r}") from e

        for mask in (16, 8, 4, 2, 1):
            if even:
                mid = (lon_min + lon_max) / 2.0
                if cd & mask:
                    lon_min = mid
                else:
                    lon_max = mid
            else:
                mid = (lat_min + lat_max) / 2.0
                if cd & mask:
                    lat_min = mid
                else:
                    lat_max = mid
            even = not even

    return lat_min, lat_max, lon_min, lon_max


def decode_center(geohash: str) -> tuple[float, float]:
    lat_min, lat_max, lon_min, lon_max = decode_bbox(geohash)
    return (lat_min + lat_max) / 2.0, (lon_min + lon_max) / 2.0
