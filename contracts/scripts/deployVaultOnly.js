// Redeploy ONLY the vault, keeping the existing MockUSDC + MerchantRegistry.
// Reads the current deployments/<network>.json for usdc + registry addresses,
// deploys a fresh OfflineVault against the same USDC, re-wires the registry,
// then writes the updated deployments file back.
const { ethers, network } = require("hardhat");
const fs = require("fs");
const path = require("path");

async function main() {
  const [deployer] = await ethers.getSigners();
  const outPath = path.resolve(__dirname, "..", "deployments", `${network.name}.json`);
  if (!fs.existsSync(outPath)) throw new Error(`no existing deployment: ${outPath}`);
  const prev = JSON.parse(fs.readFileSync(outPath, "utf8"));
  console.log("Deployer:", deployer.address);
  console.log("Reusing USDC:", prev.usdc);
  console.log("Reusing Registry:", prev.registry);

  const Vault = await ethers.getContractFactory("OfflineVault");
  const vault = await Vault.deploy(prev.usdc);
  await vault.waitForDeployment();
  const vaultAddr = await vault.getAddress();
  console.log("New OfflineVault:", vaultAddr);

  if (prev.registry) {
    await (await vault.setMerchantRegistry(prev.registry)).wait();
    console.log("Vault -> registry wired");
  }

  const updated = { ...prev, vault: vaultAddr, previousVault: prev.vault, deployedAt: new Date().toISOString() };
  fs.writeFileSync(outPath, JSON.stringify(updated, null, 2));
  console.log("Wrote", outPath);
}
main().catch(e => { console.error(e); process.exit(1); });
