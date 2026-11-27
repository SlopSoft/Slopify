fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=migrations");
    tonic_prost_build::configure()
        .build_server(false)
        .compile_protos(&["proto/downloader.proto"], &["proto"])?;
    Ok(())
}
