package crazypants.enderio.xp;

import io.netty.buffer.ByteBuf;

import java.security.InvalidParameterException;

import api.forgexpfluid.v1.XPFluidAPIProvider_v1;
import api.forgexpfluid.v1.XPFluidAPI_v1;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;

public class ExperienceContainer extends FluidTank {
  // Note: We extend FluidTank instead of implementing IFluidTank because it has
  // some methods we need.

  private int experienceLevel;
  private float experience;
  private int experienceTotal;
  private boolean xpDirty;
  private final int maxXp;
  
  public ExperienceContainer() {
    this(Integer.MAX_VALUE);
  }
  
  public ExperienceContainer(int maxStored) {
    super(null, 0);
    maxXp = maxStored;
  }
  
  public int getMaximumExperiance() {    
    return maxXp;
  }

  public int getExperienceLevel() {
    return experienceLevel;
  }

  public float getExperience() {
    return experience;
  }

  public int getExperienceTotal() {
    return experienceTotal;
  }

  public boolean isDirty() {
    return xpDirty;
  }
  
  public void setDirty(boolean isDirty) {
    xpDirty = isDirty;
  }
  
  public void set(ExperienceContainer xpCon) {
    experienceTotal = xpCon.experienceTotal;
    experienceLevel = xpCon.experienceLevel;
    experience = xpCon.experience;    
  }

  public int addExperience(int xpToAdd) {
    int j = maxXp - experienceTotal;
    if(xpToAdd > j) {
      xpToAdd = j;
    }

    experience += (float) xpToAdd / (float) getXpBarCapacity();
    experienceTotal += xpToAdd;
    for (; experience >= 1.0F; experience /= getXpBarCapacity()) {
      experience = (experience - 1.0F) * getXpBarCapacity();
      experienceLevel++;
    }
    xpDirty = true;
    return xpToAdd;
  }

  private int getXpBarCapacity() {
    return XpUtil.getXpBarCapacity(experienceLevel);
  }

  public int getXpBarScaled(int scale) {
    int result = (int) (experience * scale);
    return result;

  }

  public void givePlayerXp(EntityPlayer player, int levels) {
    for (int i = 0; i < levels && experienceTotal > 0; i++) {
      givePlayerXpLevel(player);
    }
  }

  public void givePlayerXpLevel(EntityPlayer player) {
    int currentXP = XpUtil.getPlayerXP(player);
    int nextLevelXP = XpUtil.getExperienceForLevel(player.experienceLevel + 1) + 1;
    int requiredXP = nextLevelXP - currentXP;

    requiredXP = Math.min(experienceTotal, requiredXP);
    player.addExperience(requiredXP);

    int newXp = experienceTotal - requiredXP;
    experience = 0;
    experienceLevel = 0;
    experienceTotal = 0;
    addExperience(newXp);
  }
  
    
  public void drainPlayerXpToReachContainerLevel(EntityPlayer player, int level) {    
    int targetXP = XpUtil.getExperienceForLevel(level);
    int requiredXP = targetXP - experienceTotal;
    if(requiredXP <= 0) {
      return;
    }
    int drainXP = Math.min(requiredXP, XpUtil.getPlayerXP(player));
    addExperience(drainXP);
    XpUtil.addPlayerXP(player, -drainXP);    
  }
  
  public void drainPlayerXpToReachPlayerLevel(EntityPlayer player, int level) {    
    int targetXP = XpUtil.getExperienceForLevel(level);
    int drainXP = XpUtil.getPlayerXP(player) - targetXP;
    if(drainXP <= 0) {
      return;
    }    
    drainXP = addExperience(drainXP);
    if(drainXP > 0) {
      XpUtil.addPlayerXP(player, -drainXP);
    }
  }
  
  public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
    if(resource == null || !canDrain(from, resource.getFluid())) {
      return null;
    }    
    return drain(from, resource.amount, doDrain);
  }

  
  public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
    XPFluidAPIProvider_v1 provider = XPFluidAPI_v1.getPreferredProvider();
    if(provider == null) {
      return null;
    }
    
    // We round down the maximum amount of XP drained.
    // This means that if maxDrain is an amount of liquid representing less than one experience point,
    // no fluid will ever be extracted.
    // As a workaround, the player can directly transfer the XP into a tank, then extract it from the tank.
    int canDrainXp = Math.min(experienceTotal, (int)Math.min(Integer.MAX_VALUE, provider.convertMBToXP(maxDrain))); 
    if(doDrain) {
      int newXp = experienceTotal - canDrainXp;
      experience = 0;
      experienceLevel = 0;
      experienceTotal = 0;
      addExperience(newXp);
    }
    // In case of non-integer mB/XP ratio, round the returned mB down.
    return provider.createFluidStack((int)provider.convertXPToMB(canDrainXp));
  }

  public boolean canFill(ForgeDirection from, Fluid fluid) {
    return fluid != null && XPFluidAPI_v1.isXPFluid(fluid);
  }
  
  public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
    if(resource == null) {
      return 0;
    }
    if(resource.amount <= 0) {
      return 0;
    }
    Fluid fluid = resource.getFluid();
    if(!canFill(from, fluid)) {
      return 0;
    }
    XPFluidAPIProvider_v1 provider = XPFluidAPI_v1.getProvider(fluid);
    if(!provider.isXPFluid(resource)) {
      return 0;
    }
    //need to do these calcs in XP instead of fluid space to avoid type overflows
    // Round down the amount of XP being added (so e.g. 30 mB / 20 mB/XP -> 1 XP)
    int xp = (int)Math.min(Integer.MAX_VALUE, provider.convertMBToXP(resource.amount));
    int xpSpace = getMaximumExperiance() - getExperienceTotal();
    int canFillXP = Math.min(xp, xpSpace);
    if(canFillXP <= 0) {
      return 0;
    }
    if(doFill) {
      addExperience(canFillXP);
    }
    // Round up the amount of liquid used, in case of fractional mB/XP ratios.
    return (int)Math.ceil(provider.convertXPToMB(canFillXP));
  }
  
  public boolean canDrain(ForgeDirection from, Fluid fluid) {
    XPFluidAPIProvider_v1 provider = XPFluidAPI_v1.getPreferredProvider();
    return provider != null && fluid == provider.getFluid();
  }
  
  public FluidTankInfo[] getTankInfo(ForgeDirection from) {
    XPFluidAPIProvider_v1 provider = XPFluidAPI_v1.getPreferredProvider();
    if(provider == null) {
      return new FluidTankInfo[0];
    }
    return new FluidTankInfo[] {
      new FluidTankInfo(getFluid(), getCapacity())
    };
  }

  @Override
  public int getCapacity() {
    if(maxXp == Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    XPFluidAPIProvider_v1 provider = XPFluidAPI_v1.getPreferredProvider();
    if(provider == null) {
      return 0;
    }
    // Round up the capacity
    return (int)Math.min(Integer.MAX_VALUE, Math.ceil(provider.convertXPToMB(maxXp)));
  }

  @Override
  public int getFluidAmount() {
	XPFluidAPIProvider_v1 provider = XPFluidAPI_v1.getPreferredProvider();
	if(provider == null) {
	  return 0;
	}
	// Round down the stored amount
	return (int)Math.min(Integer.MAX_VALUE, Math.floor(provider.convertXPToMB(experienceTotal)));
  }
  
  @Override
public FluidTank readFromNBT(NBTTagCompound nbtRoot) {
    experienceLevel = nbtRoot.getInteger("experienceLevel");
    experienceTotal = nbtRoot.getInteger("experienceTotal");
    experience = nbtRoot.getFloat("experience");
    return this;
  }
  
  
  @Override
public NBTTagCompound writeToNBT(NBTTagCompound nbtRoot) {
    nbtRoot.setInteger("experienceLevel", experienceLevel);
    nbtRoot.setInteger("experienceTotal", experienceTotal);
    nbtRoot.setFloat("experience", experience);
    return nbtRoot;
  }
   
  public void toBytes(ByteBuf buf) {
    buf.writeInt(experienceTotal);
    buf.writeInt(experienceLevel);
    buf.writeFloat(experience);    
  }
  
  public void fromBytes(ByteBuf buf) {
    experienceTotal = buf.readInt();
    experienceLevel = buf.readInt();
    experience = buf.readFloat();
  }


  @Override
  public FluidStack getFluid() {
    XPFluidAPIProvider_v1 provider = XPFluidAPI_v1.getPreferredProvider();
    if(provider == null) {
      return new FluidStack(FluidRegistry.WATER, 0);
    }
    return provider.createFluidStack(getFluidAmount());
  }

  @Override
  public FluidTankInfo getInfo() {
    return getTankInfo(ForgeDirection.UNKNOWN)[0];
  }

  @Override
  public int fill(FluidStack resource, boolean doFill) {
    return fill(ForgeDirection.UNKNOWN, resource, doFill);
  }

  @Override
  public FluidStack drain(int maxDrain, boolean doDrain) {
    return drain(ForgeDirection.UNKNOWN, maxDrain, doDrain);
  }

  @Override
  public void setFluid(FluidStack fluid) {
    experience = 0;
    experienceLevel = 0;
    experienceTotal = 0;
    if (fluid != null && fluid.getFluid() != null) {
      XPFluidAPIProvider_v1 provider = XPFluidAPI_v1.getProvider(fluid);
      if (provider != null) {
        // Round down the amount of XP being added.
        addExperience((int)Math.min(Integer.MAX_VALUE, provider.convertMBToXP(fluid.amount)));
      } else {
        throw new InvalidParameterException(fluid.getFluid() + " is not an XP fluid");
      }
    }
    xpDirty = true;
  }

  @Override
  public void setCapacity(int capacity) {
    throw new InvalidParameterException();
  }

}
