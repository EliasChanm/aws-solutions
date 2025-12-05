const sharp = require('sharp');
const { S3Client, GetObjectCommand } = require('@aws-sdk/client-s3');

const s3Client = new S3Client();
const BUCKET_NAME = process.env.BUCKET_NAME;
const SECRET_KEY = process.env.SECRET_KEY;
const MAX_DIMENSION = 4096;
const MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB

exports.handler = async (event) => {
  try {
    // Validate custom header
    const headers = event.headers || {};
    const cfSecret = headers['x-origin-verify'] || headers['X-Origin-Verify'];

    if (cfSecret !== SECRET_KEY) {
      return {
        statusCode: 403,
        body: JSON.stringify({ error: 'Forbidden' })
      };
    }

    // Extract paths and parameters
    const path = (event.path || '').replace(/^\//, '');
    const params = event.queryStringParameters || {};

    if (!path) {
      return {
        statusCode: 400,
        body: JSON.stringify({ error: 'Missing image path' })
      };
    }

    // Analyze and validate parameters
    const width = parseInt(params.width || '200', 10);
    const height = parseInt(params.height || '200', 10);

    if (isNaN(width) || isNaN(height)) {
      return {
        statusCode: 400,
        body: JSON.stringify({ error: 'Invalid width or height parameter' })
      };
    }

    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
      return {
        statusCode: 400,
        body: JSON.stringify({ error: `Width and height must be between 1 and ${MAX_DIMENSION}` })
      };
    }

    // Retrieve images from S3
    const getObjectCommand = new GetObjectCommand({
      Bucket: BUCKET_NAME,
      Key: path
    });

    const s3Response = await s3Client.send(getObjectCommand);
    const contentLength = s3Response.ContentLength || 0;

    if (contentLength > MAX_FILE_SIZE) {
      return {
        statusCode: 413,
        body: JSON.stringify({ error: 'Image file too large' })
      };
    }

    // Read image data
    const imageBuffer = await streamToBuffer(s3Response.Body);

    // Detect the format of the original image
    const image = sharp(imageBuffer);
    const metadata = await image.metadata();

    // Determine the output format (supports format parameter or automatically judges based on the original image)
    const requestedFormat = params.format?.toLowerCase();
    let outputFormat = requestedFormat || metadata.format;

    // Restrict supported formats
    const supportedFormats = ['jpeg', 'jpg', 'png', 'webp'];
    if (!supportedFormats.includes(outputFormat)) {
      outputFormat = 'jpeg'; // default JPEG
    }
    if (outputFormat === 'jpg') outputFormat = 'jpeg';

    // process images
    let processedImage = sharp(imageBuffer)
      .resize(width, height, {
        fit: 'inside',
        withoutEnlargement: false
      });

    // Apply different output options according to the format
    if (outputFormat === 'png') {
      processedImage = processedImage.png({ quality: 85, compressionLevel: 8 });
    } else if (outputFormat === 'webp') {
      processedImage = processedImage.webp({ quality: 85 });
    } else {
      // JPEG：If the original image has transparent channels, add a white background
      if (metadata.hasAlpha) {
        processedImage = processedImage.flatten({ background: '#ffffff' });
      }
      processedImage = processedImage.jpeg({ quality: 85, progressive: true });
    }

    const outputBuffer = await processedImage.toBuffer();

    return {
      statusCode: 200,
      headers: {
        'Content-Type': `image/${outputFormat}`,
        'Cache-Control': 'public, max-age=86400'
      },
      body: outputBuffer.toString('base64'),
      isBase64Encoded: true
    };

  } catch (error) {
    console.error('Error processing image:', error);

    // AWS SDK v3 Error Handling
    if (error.name === 'NoSuchKey' || error.$metadata?.httpStatusCode === 404) {
      return {
        statusCode: 404,
        body: JSON.stringify({ error: 'Image not found' })
      };
    }

    if (error.name === 'AccessDenied' || error.$metadata?.httpStatusCode === 403) {
      return {
        statusCode: 403,
        body: JSON.stringify({ error: 'Access denied' })
      };
    }

    if (error.message && error.message.includes('Input buffer')) {
      return {
        statusCode: 400,
        body: JSON.stringify({ error: 'Invalid image format' })
      };
    }

    return {
      statusCode: 500,
      body: JSON.stringify({ error: 'Internal server error' })
    };
  }
};

// Auxiliary function: Convert Stream to Buffer
async function streamToBuffer(stream) {
  const chunks = [];
  for await (const chunk of stream) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

