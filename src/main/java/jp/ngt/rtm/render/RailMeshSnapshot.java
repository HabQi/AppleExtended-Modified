/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/*
 * AppleExtended
 *
 * Immutable CPU-only input and output for asynchronous static rail mesh builds.
 */
package jp.ngt.rtm.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

final class RailMeshSnapshot {
    static final int VERTEX_STRIDE_BYTES = 56;

    private final float[] matrices;
    private final int[] brightness;
    private final GroupData[] groups;
    private final boolean[][] selectedGroups;
    private final int vertexCount;

    RailMeshSnapshot(float[] matrices, int[] brightness, GroupData[] groups,
                     boolean[][] selectedGroups, int vertexCount) {
        this.matrices = matrices;
        this.brightness = brightness;
        this.groups = groups;
        this.selectedGroups = selectedGroups;
        this.vertexCount = vertexCount;
    }

    RailMeshData build() {
        long byteCount = (long) this.vertexCount * VERTEX_STRIDE_BYTES;
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Rail mesh is too large: " + byteCount + " bytes");
        }

        ByteBuffer bytes = ByteBuffer.allocateDirect((int) byteCount).order(ByteOrder.nativeOrder());
        IntBuffer output = bytes.asIntBuffer();
        int writtenVertices = 0;
        for (int section = 0; section < this.selectedGroups.length; ++section) {
            int matrixOffset = section << 4;
            boolean[] selection = this.selectedGroups[section];
            for (int groupIndex = 0; groupIndex < selection.length; ++groupIndex) {
                if (!selection[groupIndex]) {
                    continue;
                }
                GroupData group = this.groups[groupIndex];
                for (int vertexIndex = 0; vertexIndex < group.vertexCount; ++vertexIndex) {
                    int vertexOffset = vertexIndex * 5;
                    float x = group.vertices[vertexOffset];
                    float y = group.vertices[vertexOffset + 1];
                    float z = group.vertices[vertexOffset + 2];
                    output.put(Float.floatToRawIntBits(transformX(x, y, z, matrixOffset)));
                    output.put(Float.floatToRawIntBits(transformY(x, y, z, matrixOffset)));
                    output.put(Float.floatToRawIntBits(transformZ(x, y, z, matrixOffset)));
                    output.put(Float.floatToRawIntBits(group.vertices[vertexOffset + 3]));
                    output.put(Float.floatToRawIntBits(group.vertices[vertexOffset + 4]));
                    output.put(-1);

                    int normalOffset = vertexIndex * 3;
                    float nx = transformVectorX(group.normals[normalOffset], group.normals[normalOffset + 1],
                            group.normals[normalOffset + 2], matrixOffset);
                    float ny = transformVectorY(group.normals[normalOffset], group.normals[normalOffset + 1],
                            group.normals[normalOffset + 2], matrixOffset);
                    float nz = transformVectorZ(group.normals[normalOffset], group.normals[normalOffset + 1],
                            group.normals[normalOffset + 2], matrixOffset);
                    float normalLength = length(nx, ny, nz);
                    if (normalLength > 0.0F) {
                        nx /= normalLength;
                        ny /= normalLength;
                        nz /= normalLength;
                    } else {
                        nx = 0.0F;
                        ny = 1.0F;
                        nz = 0.0F;
                    }
                    output.put(packNormal(nx, ny, nz));
                    output.put(this.brightness[section]);

                    int midOffset = vertexIndex * 2;
                    output.put(Float.floatToRawIntBits(group.midTexCoords[midOffset]));
                    output.put(Float.floatToRawIntBits(group.midTexCoords[midOffset + 1]));

                    int tangentOffset = vertexIndex * 4;
                    float tx = transformVectorX(group.tangents[tangentOffset], group.tangents[tangentOffset + 1],
                            group.tangents[tangentOffset + 2], matrixOffset);
                    float ty = transformVectorY(group.tangents[tangentOffset], group.tangents[tangentOffset + 1],
                            group.tangents[tangentOffset + 2], matrixOffset);
                    float tz = transformVectorZ(group.tangents[tangentOffset], group.tangents[tangentOffset + 1],
                            group.tangents[tangentOffset + 2], matrixOffset);
                    float normalProjection = tx * nx + ty * ny + tz * nz;
                    tx -= nx * normalProjection;
                    ty -= ny * normalProjection;
                    tz -= nz * normalProjection;
                    float tangentLength = length(tx, ty, tz);
                    if (tangentLength > 0.0F) {
                        tx /= tangentLength;
                        ty /= tangentLength;
                        tz /= tangentLength;
                    } else {
                        float[] fallback = fallbackTangent(nx, ny, nz);
                        tx = fallback[0];
                        ty = fallback[1];
                        tz = fallback[2];
                    }
                    output.put(Float.floatToRawIntBits(tx));
                    output.put(Float.floatToRawIntBits(ty));
                    output.put(Float.floatToRawIntBits(tz));
                    output.put(Float.floatToRawIntBits(group.tangents[tangentOffset + 3]));
                    ++writtenVertices;
                }
            }
        }
        if (writtenVertices != this.vertexCount) {
            throw new IllegalStateException("Rail vertex count changed while building");
        }
        bytes.position(0);
        bytes.limit(writtenVertices * VERTEX_STRIDE_BYTES);
        return new RailMeshData(bytes, writtenVertices);
    }

    private float transformX(float x, float y, float z, int offset) {
        return x * this.matrices[offset] + y * this.matrices[offset + 4]
                + z * this.matrices[offset + 8] + this.matrices[offset + 12];
    }

    private float transformY(float x, float y, float z, int offset) {
        return x * this.matrices[offset + 1] + y * this.matrices[offset + 5]
                + z * this.matrices[offset + 9] + this.matrices[offset + 13];
    }

    private float transformZ(float x, float y, float z, int offset) {
        return x * this.matrices[offset + 2] + y * this.matrices[offset + 6]
                + z * this.matrices[offset + 10] + this.matrices[offset + 14];
    }

    private float transformVectorX(float x, float y, float z, int offset) {
        return x * this.matrices[offset] + y * this.matrices[offset + 4] + z * this.matrices[offset + 8];
    }

    private float transformVectorY(float x, float y, float z, int offset) {
        return x * this.matrices[offset + 1] + y * this.matrices[offset + 5] + z * this.matrices[offset + 9];
    }

    private float transformVectorZ(float x, float y, float z, int offset) {
        return x * this.matrices[offset + 2] + y * this.matrices[offset + 6] + z * this.matrices[offset + 10];
    }

    private static float length(float x, float y, float z) {
        float lengthSquared = x * x + y * y + z * z;
        return Float.isFinite(lengthSquared) && lengthSquared > 1.0E-12F
                ? (float) Math.sqrt(lengthSquared) : 0.0F;
    }

    static float[] fallbackTangent(float nx, float ny, float nz) {
        float tx;
        float ty;
        float tz;
        if (Math.abs(nx) < 0.9F) {
            tx = 1.0F - nx * nx;
            ty = -nx * ny;
            tz = -nx * nz;
        } else {
            tx = -ny * nx;
            ty = 1.0F - ny * ny;
            tz = -ny * nz;
        }
        float tangentLength = length(tx, ty, tz);
        if (tangentLength <= 0.0F) {
            return new float[]{0.0F, 0.0F, 1.0F};
        }
        return new float[]{tx / tangentLength, ty / tangentLength, tz / tangentLength};
    }

    static int packNormal(float x, float y, float z) {
        int nx = Math.round(Math.max(-1.0F, Math.min(1.0F, x)) * 127.0F);
        int ny = Math.round(Math.max(-1.0F, Math.min(1.0F, y)) * 127.0F);
        int nz = Math.round(Math.max(-1.0F, Math.min(1.0F, z)) * 127.0F);
        return nx & 0xFF | (ny & 0xFF) << 8 | (nz & 0xFF) << 16;
    }

    static final class GroupData {
        final String name;
        final float[] vertices;
        final float[] normals;
        final float[] midTexCoords;
        final float[] tangents;
        final int vertexCount;

        GroupData(String name, float[] vertices, float[] normals, float[] midTexCoords, float[] tangents) {
            this.name = name;
            this.vertices = vertices;
            this.normals = normals;
            this.midTexCoords = midTexCoords;
            this.tangents = tangents;
            this.vertexCount = normals.length / 3;
        }
    }
}

final class RailMeshData {
    final ByteBuffer vertices;
    final int vertexCount;
    final int byteSize;

    RailMeshData(ByteBuffer vertices, int vertexCount) {
        this.vertices = vertices;
        this.vertexCount = vertexCount;
        this.byteSize = vertices.remaining();
    }
}
