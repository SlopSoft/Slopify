fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=migrations");
    println!("cargo:rerun-if-changed=proto/downloader.proto");
    tonic_prost_build::configure()
        .build_server(false)
        .compile_protos(&["proto/downloader.proto"], &["proto"])?;
    Ok(())
}
