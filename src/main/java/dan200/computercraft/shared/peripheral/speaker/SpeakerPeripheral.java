/*
 * This file is part of ComputerCraft - http://www.computercraft.info
 * Copyright Daniel Ratcliffe, 2011-2019. Do not distribute without permission.
 * Send enquiries to dratcliffe@gmail.com
 */

package dan200.computercraft.shared.peripheral.speaker;

import dan200.computercraft.ComputerCraft;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.network.play.server.SPlaySoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.state.properties.NoteBlockInstrument;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ResourceLocationException;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicInteger;

import static dan200.computercraft.core.apis.ArgumentHelper.getString;
import static dan200.computercraft.core.apis.ArgumentHelper.optReal;

public abstract class SpeakerPeripheral implements IPeripheral
{
    private long m_clock = 0;
    private long m_lastPlayTime = 0;
    private final AtomicInteger m_notesThisTick = new AtomicInteger();

    public void update()
    {
        m_clock++;
        m_notesThisTick.set( 0 );
    }

    public abstract World getWorld();

    public abstract Vec3d getPosition();

    public boolean madeSound( long ticks )
    {
        return m_clock - m_lastPlayTime <= ticks;
    }

    @Nonnull
    @Override
    public String getType()
    {
        return "speaker";
    }

    @Nonnull
    @Override
    public String[] getMethodNames()
    {
        return new String[] {
            "playSound",
            "playNote",
        };
    }

    @Override
    public Object[] callMethod( @Nonnull IComputerAccess computerAccess, @Nonnull ILuaContext context, int methodIndex, @Nonnull Object[] args ) throws LuaException
    {
        switch( methodIndex )
        {
            case 0: // playSound
            {
                String name = getString( args, 0 );
                float volume = (float) optReal( args, 1, 1.0 );
                float pitch = (float) optReal( args, 2, 1.0 );

                ResourceLocation identifier;
                try
                {
                    identifier = new ResourceLocation( name );
                }
                catch( ResourceLocationException e )
                {
                    throw new LuaException( "Malformed sound name '" + name + "' " );
                }

                return new Object[] { playSound( context, identifier, volume, pitch, false ) };
            }

            case 1: // playNote
                return playNote( args, context );

            default:
                throw new IllegalStateException( "Method index out of range!" );
        }
    }

    @Nonnull
    private synchronized Object[] playNote( Object[] arguments, ILuaContext context ) throws LuaException
    {
        String name = getString( arguments, 0 );
        float volume = (float) optReal( arguments, 1, 1.0 );
        float pitch = (float) optReal( arguments, 2, 1.0 );

        NoteBlockInstrument instrument = null;
        for( NoteBlockInstrument testInstrument : NoteBlockInstrument.values() )
        {
            if( testInstrument.getName().equalsIgnoreCase( name ) )
            {
                instrument = testInstrument;
                break;
            }
        }

        // Check if the note exists
        if( instrument == null )
        {
            throw new LuaException( "Invalid instrument, \"" + name + "\"!" );
        }

        // If the resource location for note block notes changes, this method call will need to be updated
        boolean success = playSound( context, instrument.getSound().getRegistryName(), volume, (float) Math.pow( 2.0, (pitch - 12.0) / 12.0 ), true );

        if( success ) m_notesThisTick.incrementAndGet();
        return new Object[] { success };
    }

    private synchronized boolean playSound( ILuaContext context, ResourceLocation name, float volume, float pitch, boolean isNote ) throws LuaException
    {
        if( m_clock - m_lastPlayTime < TileSpeaker.MIN_TICKS_BETWEEN_SOUNDS &&
            (!isNote || m_clock - m_lastPlayTime != 0 || m_notesThisTick.get() >= ComputerCraft.maxNotesPerTick) )
        {
            // Rate limiting occurs when we've already played a sound within the last tick, or we've
            // played more notes than allowable within the current tick.
            return false;
        }

        World world = getWorld();
        Vec3d pos = getPosition();

        context.issueMainThreadTask( () -> {
            MinecraftServer server = world.getServer();
            if( server == null ) return null;

            float adjVolume = Math.min( volume, 3.0f );
            server.getPlayerList().sendToAllNearExcept(
                null, pos.x, pos.y, pos.z, adjVolume > 1.0f ? 16 * adjVolume : 16.0, world.dimension.getType(),
                new SPlaySoundPacket( name, SoundCategory.RECORDS, pos, adjVolume, pitch )
            );
            return null;
        } );

        m_lastPlayTime = m_clock;
        return true;
    }
}

