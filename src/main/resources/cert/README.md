# SSL Certificate Directory

This directory is where you should place your SSL certificate files for HTTPS support.

## Required Files

You need to generate a PKCS12 keystore file and place it in this directory. The file should be named according to your domain (e.g., `your-domain.p12`).

## How to Generate SSL Certificate

Please refer to the SSL Certificate Management section in the main README.md file for detailed instructions on how to:

1. Obtain SSL certificates from Let's Encrypt
2. Generate a PKCS12 keystore
3. Configure the application to use your certificates

## Security Note

**IMPORTANT**: Never commit your SSL certificate files to the repository. The `.gitignore` file is configured to exclude `.p12`, `.pem`, `.key`, and `.crt` files from this directory.

Instead, you should generate and manage your certificates separately and copy them to this directory on your deployment server.