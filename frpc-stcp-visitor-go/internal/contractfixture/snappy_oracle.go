package contractfixture

import (
	"bytes"
	"fmt"
	"io"

	libcrypto "github.com/fatedier/golib/crypto"
	"github.com/golang/snappy"
)

const (
	snappyRawCompressedPath         = "compression/snappy/raw/compressed.bin"
	snappyFramedBoundaryPath        = "compression/snappy/framed/boundary-65537.bin"
	snappyFramedCompressedPath      = "compression/snappy/framed/compressed.bin"
	snappyFramedUncompressedPath    = "compression/snappy/framed/uncompressed.bin"
	snappyCFBFramedCompressedPath   = "compression/snappy/cfb/compressed.bin"
	snappyCompressedPayloadSize     = 20
	snappyUncompressedPayloadSize   = 256
	snappyBoundaryPayloadSize       = 65_537
	snappyChunkTypeCompressedData   = byte(0x00)
	snappyChunkTypeUncompressedData = byte(0x01)
	snappyChunkTypeStreamIdentifier = byte(0xff)
	snappyFrameHeaderSize           = 4
	snappyFrameChecksumSize         = 4
	snappyEncryptionIVStart         = byte(0x70)
)

type snappyArtifact struct {
	id       string
	path     string
	layer    string
	expected string
	data     []byte
}

type snappyFramedChunk struct {
	typeByte byte
	body     []byte
}

func buildSnappyArtifacts() ([]snappyArtifact, error) {
	compressedPayload := buildSnappyCompressedPayload()
	uncompressedPayload := buildSnappyUncompressedPayload()
	boundaryPayload := buildSnappyBoundaryPayload()

	rawCompressed := snappy.Encode(nil, compressedPayload)
	framedCompressed, err := encodeSnappyFramed(compressedPayload)
	if err != nil {
		return nil, fmt.Errorf("encode compressed snappy fixture")
	}
	framedUncompressed, err := encodeSnappyFramed(uncompressedPayload)
	if err != nil {
		return nil, fmt.Errorf("encode uncompressed snappy fixture")
	}
	framedBoundary, err := encodeSnappyFramed(boundaryPayload)
	if err != nil {
		return nil, fmt.Errorf("encode boundary snappy fixture")
	}
	cfbFramedCompressed, err := encodeSnappyCFBFramed(
		compressedPayload,
		fixedBytes(snappyEncryptionIVStart, 16),
	)
	if err != nil {
		return nil, err
	}

	return []snappyArtifact{
		{
			id:       "snappy-cfb-framed-compressed",
			path:     snappyCFBFramedCompressedPath,
			layer:    "snappy-cfb-framed",
			expected: "wire=CFB(Snappy framed); decode-order=CFB-then-Snappy",
			data:     cfbFramedCompressed,
		},
		{
			id:       "snappy-framed-boundary-65537",
			path:     snappyFramedBoundaryPath,
			layer:    "snappy-framed",
			expected: "chunks=compressed,uncompressed; decode=repeat-byte(0x61,65537)",
			data:     framedBoundary,
		},
		{
			id:       "snappy-framed-compressed",
			path:     snappyFramedCompressedPath,
			layer:    "snappy-framed",
			expected: "chunks=compressed; decode=repeat-byte(0x61,20)",
			data:     framedCompressed,
		},
		{
			id:       "snappy-framed-uncompressed",
			path:     snappyFramedUncompressedPath,
			layer:    "snappy-framed",
			expected: "chunks=uncompressed; decode=byte-sequence-mod-256(256)",
			data:     framedUncompressed,
		},
		{
			id:       "snappy-raw-compressed",
			path:     snappyRawCompressedPath,
			layer:    "snappy-raw",
			expected: "decode=repeat-byte(0x61,20)",
			data:     rawCompressed,
		},
	}, nil
}

func encodeSnappyFramed(payload []byte) ([]byte, error) {
	var output bytes.Buffer
	writer := snappy.NewWriter(&output)
	if err := writeAll(writer, payload); err != nil {
		return nil, err
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}
	return append([]byte(nil), output.Bytes()...), nil
}

func encodeSnappyCFBFramed(payload, iv []byte) ([]byte, error) {
	var output bytes.Buffer
	err := withFixedCryptoRand(iv, func() error {
		encrypted, err := libcrypto.NewWriter(&output, []byte(syntheticSTCPSecret))
		if err != nil {
			return err
		}
		writer := snappy.NewWriter(encrypted)
		if err := writeAll(writer, payload); err != nil {
			return err
		}
		return writer.Close()
	})
	if err != nil {
		return nil, fmt.Errorf("encode deterministic CFB and snappy fixture")
	}
	return append([]byte(nil), output.Bytes()...), nil
}

func validateSnappyArtifacts(files map[string][]byte) error {
	compressedPayload := buildSnappyCompressedPayload()
	rawDecoded, err := snappy.Decode(nil, files[snappyRawCompressedPath])
	if err != nil || !bytes.Equal(rawDecoded, compressedPayload) {
		return fmt.Errorf("validate raw snappy fixture")
	}

	compressedChunks, err := validateSnappyFramedVector(
		files[snappyFramedCompressedPath],
		compressedPayload,
		[]byte{snappyChunkTypeCompressedData},
	)
	if err != nil {
		return fmt.Errorf("validate compressed snappy fixture")
	}
	if len(compressedChunks) != 1 ||
		!bytes.Equal(compressedChunks[0].body[snappyFrameChecksumSize:], files[snappyRawCompressedPath]) {
		return fmt.Errorf("framed snappy raw block differs from the raw oracle")
	}

	if _, err := validateSnappyFramedVector(
		files[snappyFramedUncompressedPath],
		buildSnappyUncompressedPayload(),
		[]byte{snappyChunkTypeUncompressedData},
	); err != nil {
		return fmt.Errorf("validate uncompressed snappy fixture")
	}
	if _, err := validateSnappyFramedVector(
		files[snappyFramedBoundaryPath],
		buildSnappyBoundaryPayload(),
		[]byte{snappyChunkTypeCompressedData, snappyChunkTypeUncompressedData},
	); err != nil {
		return fmt.Errorf("validate boundary snappy fixture")
	}

	ciphertext := files[snappyCFBFramedCompressedPath]
	expectedIV := fixedBytes(snappyEncryptionIVStart, 16)
	if len(ciphertext) < len(expectedIV) || !bytes.Equal(ciphertext[:len(expectedIV)], expectedIV) {
		return fmt.Errorf("snappy CFB fixture IV differs from the deterministic oracle")
	}
	var decrypted []byte
	err = withFRPCryptoSalt(func() error {
		var readErr error
		decrypted, readErr = readBounded(
			libcrypto.NewReader(bytes.NewReader(ciphertext), []byte(syntheticSTCPSecret)),
			maxCanonicalFileSize,
		)
		return readErr
	})
	if err != nil || !bytes.Equal(decrypted, files[snappyFramedCompressedPath]) {
		return fmt.Errorf("validate CFB outside snappy wrapping order")
	}
	return nil
}

func validateSnappyFramedVector(encoded, expected []byte, expectedTypes []byte) ([]snappyFramedChunk, error) {
	chunks, err := parseSnappyFramed(encoded)
	if err != nil {
		return nil, err
	}
	types := make([]byte, len(chunks))
	for index, chunk := range chunks {
		types[index] = chunk.typeByte
	}
	if !bytes.Equal(types, expectedTypes) {
		return nil, fmt.Errorf("snappy framed chunk types differ")
	}
	decoded, err := readBounded(snappy.NewReader(bytes.NewReader(encoded)), maxCanonicalFileSize)
	if err != nil || !bytes.Equal(decoded, expected) {
		return nil, fmt.Errorf("snappy framed payload differs")
	}
	return chunks, nil
}

func parseSnappyFramed(encoded []byte) ([]snappyFramedChunk, error) {
	chunks := make([]snappyFramedChunk, 0, 2)
	identifierSeen := false
	for position := 0; position < len(encoded); {
		if len(encoded)-position < snappyFrameHeaderSize {
			return nil, fmt.Errorf("snappy frame header is truncated")
		}
		typeByte := encoded[position]
		length := int(encoded[position+1]) | int(encoded[position+2])<<8 | int(encoded[position+3])<<16
		bodyStart := position + snappyFrameHeaderSize
		if length > len(encoded)-bodyStart {
			return nil, fmt.Errorf("snappy frame body is truncated")
		}
		bodyEnd := bodyStart + length
		body := encoded[bodyStart:bodyEnd]
		switch typeByte {
		case snappyChunkTypeStreamIdentifier:
			if !bytes.Equal(body, []byte("sNaPpY")) {
				return nil, fmt.Errorf("snappy stream identifier differs")
			}
			identifierSeen = true
		case snappyChunkTypeCompressedData, snappyChunkTypeUncompressedData:
			if !identifierSeen || len(body) < snappyFrameChecksumSize {
				return nil, fmt.Errorf("snappy data chunk is invalid")
			}
			chunks = append(chunks, snappyFramedChunk{typeByte: typeByte, body: body})
		default:
			return nil, fmt.Errorf("snappy writer emitted an unexpected chunk type")
		}
		position = bodyEnd
	}
	if !identifierSeen || len(chunks) == 0 {
		return nil, fmt.Errorf("snappy framed fixture is incomplete")
	}
	return chunks, nil
}

func validateSnappyCFBChunkPlan(reader io.Reader) error {
	var decoded []byte
	err := withFRPCryptoSalt(func() error {
		var readErr error
		decoded, readErr = readBounded(
			snappy.NewReader(libcrypto.NewReader(reader, []byte(syntheticSTCPSecret))),
			maxCanonicalFileSize,
		)
		return readErr
	})
	if err != nil || !bytes.Equal(decoded, buildSnappyCompressedPayload()) {
		return fmt.Errorf("chunked CFB and snappy payload differs")
	}
	return nil
}

func buildSnappyCompressedPayload() []byte {
	return bytes.Repeat([]byte{'a'}, snappyCompressedPayloadSize)
}

func buildSnappyUncompressedPayload() []byte {
	payload := make([]byte, snappyUncompressedPayloadSize)
	for index := range payload {
		payload[index] = byte(index)
	}
	return payload
}

func buildSnappyBoundaryPayload() []byte {
	return bytes.Repeat([]byte{'a'}, snappyBoundaryPayloadSize)
}
