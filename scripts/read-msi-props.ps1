param([string]$Msi)
$installer = New-Object -ComObject WindowsInstaller.Installer
$db = $installer.GetType().InvokeMember('OpenDatabase','InvokeMethod',$null,$installer,@($Msi,0))
$query = "SELECT Property,Value FROM Property"
$view = $db.GetType().InvokeMember('OpenView','InvokeMethod',$null,$db,@($query))
$view.GetType().InvokeMember('Execute','InvokeMethod',$null,$view,$null) | Out-Null
while ($true) {
    $rec = $view.GetType().InvokeMember('Fetch','InvokeMethod',$null,$view,$null)
    if ($null -eq $rec) { break }
    $name = $rec.GetType().InvokeMember('StringData','GetProperty',$null,$rec,@(1))
    $val  = $rec.GetType().InvokeMember('StringData','GetProperty',$null,$rec,@(2))
    if ($name -in 'ALLUSERS','MSIINSTALLPERUSER','ProductName','Manufacturer','ProductVersion','UpgradeCode') {
        Write-Output "$name=$val"
    }
}
